from __future__ import annotations

import argparse
import json
from pathlib import Path

from .certs import certificate_sha256, generate_auth_token, generate_self_signed_certificate
from .config import ServerConfig, load_config, save_config
from .env import config_path_from_env, download_dir_from_env, env_path, state_dir_from_env, ENV_PHONE_QUEUE_DIR
from .httpd import create_server
from .outbox import queue_phone_file
from .pairing import build_pairing_payload, build_pairing_uri


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="lss-server")
    subparsers = parser.add_subparsers(dest="command", required=True)

    init_parser = subparsers.add_parser("init", help="initialize config and certificate")
    init_parser.add_argument("--state-dir", type=Path, default=state_dir_from_env(Path("./state")))
    init_parser.add_argument("--server-name", default="WifiShare")
    init_parser.add_argument("--listen-host", default="0.0.0.0")
    init_parser.add_argument("--listen-port", type=int, default=8443)
    init_parser.add_argument("--advertise-host", required=True)
    init_parser.add_argument("--upload-dir", type=Path, default=None)
    init_parser.add_argument("--phone-queue-dir", type=Path, default=None)
    init_parser.add_argument("--max-upload-mb", type=int, default=1024)

    serve_parser = subparsers.add_parser("serve", help="run the HTTPS receiver")
    serve_parser.add_argument("--config", type=Path, default=config_path_from_env(Path("./state/config.json")))

    pairing_parser = subparsers.add_parser("pairing", help="print Android pairing data")
    pairing_parser.add_argument("--config", type=Path, default=config_path_from_env(Path("./state/config.json")))
    pairing_parser.add_argument(
        "--write",
        action="store_true",
        help="refresh pairing.json and pairing-uri.txt next to the config file",
    )

    phone_parser = subparsers.add_parser("phone", help="queue files for the paired phone")
    add_phone_arguments(phone_parser)
    return parser


def init_command(args: argparse.Namespace) -> int:
    state_dir: Path = args.state_dir
    state_dir.mkdir(parents=True, exist_ok=True)

    upload_dir = args.upload_dir or download_dir_from_env() or state_dir / "uploads"
    phone_queue_dir = args.phone_queue_dir or env_path(ENV_PHONE_QUEUE_DIR) or state_dir / "phone-outbox"
    cert_path = state_dir / "server.crt"
    key_path = state_dir / "server.key"
    config_path = state_dir / "config.json"
    pairing_path = state_dir / "pairing.json"
    pairing_uri_path = state_dir / "pairing-uri.txt"

    generate_self_signed_certificate(
        cert_path=cert_path,
        key_path=key_path,
        common_name=args.server_name,
        advertise_host=args.advertise_host,
    )
    token = generate_auth_token()
    config = ServerConfig(
        server_name=args.server_name,
        listen_host=args.listen_host,
        listen_port=args.listen_port,
        advertise_host=args.advertise_host,
        auth_token=token,
        cert_file=str(cert_path),
        key_file=str(key_path),
        upload_dir=str(upload_dir),
        phone_queue_dir=str(phone_queue_dir),
        max_upload_mb=args.max_upload_mb,
    )
    save_config(config_path, config)

    fingerprint = certificate_sha256(cert_path)
    pairing_payload = build_pairing_payload(config, fingerprint)
    pairing_uri = build_pairing_uri(pairing_payload)
    pairing_path.write_text(
        json.dumps(pairing_payload, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    pairing_uri_path.write_text(pairing_uri + "\n", encoding="utf-8")

    print(f"Config written to: {config_path}")
    print(f"Pairing file:      {pairing_path}")
    print(f"Pairing URI file:  {pairing_uri_path}")
    print(f"Base URL:          {config.base_url}")
    print(f"Auth token:        {config.auth_token}")
    print(f"Cert SHA-256:      {fingerprint}")
    print(f"Pairing URI:       {pairing_uri}")
    print(f"Upload directory:  {upload_dir}")
    print(f"Phone queue dir:   {phone_queue_dir}")
    return 0


def serve_command(args: argparse.Namespace) -> int:
    config = load_config(args.config)
    server = create_server(config)
    print(f"Listening on {config.listen_host}:{config.listen_port}")
    print(f"Expected Android URL: {config.base_url}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nStopping server.")
    finally:
        server.server_close()
    return 0


def pairing_command(config_path: Path, write_files: bool) -> int:
    config = load_config(config_path)
    fingerprint = certificate_sha256(Path(config.cert_file))
    payload = build_pairing_payload(config, fingerprint)
    pairing_uri = build_pairing_uri(payload)

    if write_files:
        state_dir = config_path.expanduser().resolve().parent
        (state_dir / "pairing.json").write_text(
            json.dumps(payload, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
        (state_dir / "pairing-uri.txt").write_text(pairing_uri + "\n", encoding="utf-8")

    print(json.dumps(payload, indent=2, sort_keys=True))
    print(f"Pairing URI: {pairing_uri}")
    return 0


def add_phone_arguments(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("paths", nargs="+", type=Path)
    parser.add_argument("--config", type=Path, default=config_path_from_env(Path("./state/config.json")))


def queue_phone_files_command(config_path: Path, paths: list[Path]) -> int:
    config = load_config(config_path)
    queued = []
    for path in paths:
        transfer = queue_phone_file(config.phone_queue_path, path)
        queued.append(transfer)
        print(
            f"Queued for phone: {path} -> {transfer.filename} "
            f"({transfer.size} bytes, id={transfer.transfer_id})"
        )

    print(f"Phone queue dir:   {config.phone_queue_path}")
    print(f"Queued files:      {len(queued)}")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    if args.command == "init":
        return init_command(args)
    if args.command == "serve":
        return serve_command(args)
    if args.command == "pairing":
        return pairing_command(args.config, args.write)
    if args.command == "phone":
        return queue_phone_files_command(args.config, args.paths)
    parser.error("unknown command")
    return 2


def phone_main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="phone")
    add_phone_arguments(parser)
    args = parser.parse_args(argv)
    return queue_phone_files_command(args.config, args.paths)
