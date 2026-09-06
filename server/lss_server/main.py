from __future__ import annotations

import argparse
import json
from pathlib import Path

from .certs import certificate_sha256, generate_self_signed_certificate
from .config import ServerConfig, load_config, save_config
from .env import config_path_from_env, download_dir_from_env, env_path, state_dir_from_env, ENV_PHONE_QUEUE_DIR
from .httpd import create_server
from .outbox import queue_phone_file
from .pairing import build_pairing_payload, build_pairing_uri
from .security import atomic_write_private_text, ensure_private_directory
from .tokens import ALL_SCOPES, DeviceTokenStore, parse_scopes, validate_token_expiry_days


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
    init_parser.add_argument("--max-outbox-file-mb", type=int, default=4096)
    init_parser.add_argument("--max-storage-mb", type=int, default=10240)
    init_parser.add_argument("--min-free-mb", type=int, default=512)
    init_parser.add_argument("--max-concurrent-requests", type=int, default=16)
    init_parser.add_argument("--request-timeout-seconds", type=int, default=120)
    init_parser.add_argument("--tls-handshake-timeout-seconds", type=int, default=10)
    init_parser.add_argument("--requests-per-minute", type=int, default=240)
    init_parser.add_argument("--device-name", default="Initial Android device")
    init_parser.add_argument("--token-expires-days", type=int, default=365)
    init_parser.add_argument(
        "--no-write-pairing",
        action="store_true",
        help="print pairing data without writing pairing.json or pairing-uri.txt",
    )

    serve_parser = subparsers.add_parser("serve", help="run the HTTPS receiver")
    serve_parser.add_argument("--config", type=Path, default=config_path_from_env(Path("./state/config.json")))

    pairing_parser = subparsers.add_parser("pairing", help="print Android pairing data")
    pairing_parser.add_argument("--config", type=Path, default=config_path_from_env(Path("./state/config.json")))
    pairing_parser.add_argument(
        "--write",
        action="store_true",
        help="refresh pairing.json and pairing-uri.txt next to the config file",
    )
    pairing_parser.add_argument("--device-name", default="Android device")
    pairing_parser.add_argument("--token-expires-days", type=int, default=365)
    pairing_parser.add_argument(
        "--scopes",
        default=",".join(sorted(ALL_SCOPES)),
        help="comma-separated token scopes: upload,outbox.read,outbox.ack",
    )

    configure_parser = subparsers.add_parser("configure", help="update limits without rotating credentials")
    configure_parser.add_argument("--config", type=Path, default=config_path_from_env(Path("./state/config.json")))
    configure_parser.add_argument("--max-upload-mb", type=int)
    configure_parser.add_argument("--max-outbox-file-mb", type=int)
    configure_parser.add_argument("--max-storage-mb", type=int)
    configure_parser.add_argument("--min-free-mb", type=int)
    configure_parser.add_argument("--max-concurrent-requests", type=int)
    configure_parser.add_argument("--request-timeout-seconds", type=int)
    configure_parser.add_argument("--tls-handshake-timeout-seconds", type=int)
    configure_parser.add_argument("--requests-per-minute", type=int)

    devices_parser = subparsers.add_parser("devices", help="list, revoke, or migrate device tokens")
    devices_parser.add_argument("action", choices=("list", "revoke", "migrate-legacy"))
    devices_parser.add_argument("device_id", nargs="?")
    devices_parser.add_argument("--config", type=Path, default=config_path_from_env(Path("./state/config.json")))

    phone_parser = subparsers.add_parser("phone", help="queue files for the paired phone")
    add_phone_arguments(phone_parser)
    return parser


def init_command(args: argparse.Namespace) -> int:
    state_dir: Path = args.state_dir
    upload_dir = args.upload_dir or download_dir_from_env() or state_dir / "uploads"
    phone_queue_dir = args.phone_queue_dir or env_path(ENV_PHONE_QUEUE_DIR) or state_dir / "phone-outbox"
    cert_path = state_dir / "server.crt"
    key_path = state_dir / "server.key"
    config_path = state_dir / "config.json"
    pairing_path = state_dir / "pairing.json"
    pairing_uri_path = state_dir / "pairing-uri.txt"
    token_store_path = state_dir / "device-tokens.json"
    config = ServerConfig(
        server_name=args.server_name,
        listen_host=args.listen_host,
        listen_port=args.listen_port,
        advertise_host=args.advertise_host,
        auth_token="",
        cert_file=str(cert_path),
        key_file=str(key_path),
        upload_dir=str(upload_dir),
        phone_queue_dir=str(phone_queue_dir),
        token_store_file=str(token_store_path),
        max_upload_mb=args.max_upload_mb,
        max_outbox_file_mb=args.max_outbox_file_mb,
        max_storage_mb=args.max_storage_mb,
        min_free_mb=args.min_free_mb,
        max_concurrent_requests=args.max_concurrent_requests,
        request_timeout_seconds=args.request_timeout_seconds,
        tls_handshake_timeout_seconds=args.tls_handshake_timeout_seconds,
        requests_per_minute=args.requests_per_minute,
    )
    config.validate()
    validate_token_expiry_days(args.token_expires_days)

    ensure_private_directory(state_dir)
    ensure_private_directory(phone_queue_dir)
    upload_dir.mkdir(parents=True, exist_ok=True, mode=0o700)

    generate_self_signed_certificate(
        cert_path=cert_path,
        key_path=key_path,
        common_name=args.server_name,
        advertise_host=args.advertise_host,
    )
    token_store = DeviceTokenStore(token_store_path)
    token_store.reset()
    initial_token = token_store.issue(
        args.device_name,
        expires_days=args.token_expires_days,
    )
    save_config(config_path, config)

    fingerprint = certificate_sha256(cert_path)
    pairing_payload = build_pairing_payload(
        config,
        fingerprint,
        auth_token=initial_token.token,
        device_id=initial_token.device_id,
        expires_at=initial_token.expires_at,
    )
    pairing_uri = build_pairing_uri(pairing_payload)
    write_pairing_files = not args.no_write_pairing
    if write_pairing_files:
        atomic_write_private_text(
            pairing_path,
            json.dumps(pairing_payload, indent=2, sort_keys=True) + "\n",
        )
        atomic_write_private_text(pairing_uri_path, pairing_uri + "\n")

    print(f"Config written to: {config_path}")
    if write_pairing_files:
        print(f"Pairing file:      {pairing_path}")
        print(f"Pairing URI file:  {pairing_uri_path}")
    else:
        print("Pairing files:     not written")
    print(f"Base URL:          {config.base_url}")
    print(f"Device token:      {initial_token.token}")
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


def pairing_command(
    config_path: Path,
    write_files: bool,
    device_name: str,
    token_expires_days: int,
    scopes_value: str,
) -> int:
    config = load_config(config_path)
    token_store = _migrate_legacy_token(config_path, config)
    issued = token_store.issue(
        device_name,
        scopes=parse_scopes(scopes_value),
        expires_days=token_expires_days,
    )
    fingerprint = certificate_sha256(Path(config.cert_file))
    payload = build_pairing_payload(
        config,
        fingerprint,
        auth_token=issued.token,
        device_id=issued.device_id,
        expires_at=issued.expires_at,
    )
    pairing_uri = build_pairing_uri(payload)

    if write_files:
        state_dir = config_path.expanduser().resolve().parent
        atomic_write_private_text(
            state_dir / "pairing.json",
            json.dumps(payload, indent=2, sort_keys=True) + "\n",
        )
        atomic_write_private_text(state_dir / "pairing-uri.txt", pairing_uri + "\n")

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
        transfer = queue_phone_file(
            config.phone_queue_path,
            path,
            max_file_bytes=config.outbox_file_limit_bytes,
            max_total_bytes=config.storage_limit_bytes,
            min_free_bytes=config.minimum_free_bytes,
        )
        queued.append(transfer)
        print(
            f"Queued for phone: {path} -> {transfer.filename} "
            f"({transfer.size} bytes, id={transfer.transfer_id})"
        )

    print(f"Phone queue dir:   {config.phone_queue_path}")
    print(f"Queued files:      {len(queued)}")
    return 0


def configure_command(config_path: Path, args: argparse.Namespace) -> int:
    config = load_config(config_path)
    field_names = (
        "max_upload_mb",
        "max_outbox_file_mb",
        "max_storage_mb",
        "min_free_mb",
        "max_concurrent_requests",
        "request_timeout_seconds",
        "tls_handshake_timeout_seconds",
        "requests_per_minute",
    )
    changed = []
    for field_name in field_names:
        value = getattr(args, field_name)
        if value is None:
            continue
        setattr(config, field_name, value)
        changed.append(field_name)
    if not changed:
        raise ValueError("configure requires at least one setting")
    save_config(config_path.expanduser().resolve(), config)
    print(f"Config updated: {config_path.expanduser().resolve()}")
    for field_name in changed:
        print(f"  {field_name}={getattr(config, field_name)}")
    print("Restart the WifiShare service to apply these settings.")
    return 0


def devices_command(config_path: Path, action: str, device_id: str | None) -> int:
    config = load_config(config_path)
    token_store = DeviceTokenStore(config.token_store_path or config_path.parent / "device-tokens.json")
    if action == "migrate-legacy":
        migrated = bool(config.auth_token.strip())
        _migrate_legacy_token(config_path, config)
        print("Legacy token migrated." if migrated else "No legacy token to migrate.")
        return 0
    if action == "revoke":
        if not device_id:
            raise ValueError("devices revoke requires device_id")
        if not token_store.revoke(device_id):
            raise ValueError(f"unknown device_id: {device_id}")
        print(f"Revoked device: {device_id}")
        return 0

    for device in token_store.list_devices():
        status = "revoked" if device["revoked_at"] else "active"
        expiry = device["expires_at"] or "none"
        print(
            f"{device['device_id']}  {status:7}  {device['device_name']}  "
            f"expires={expiry}  scopes={','.join(device['scopes'])}"
        )
    return 0


def _migrate_legacy_token(config_path: Path, config: ServerConfig) -> DeviceTokenStore:
    token_store_path = config.token_store_path or config_path.expanduser().resolve().parent / "device-tokens.json"
    token_store = DeviceTokenStore(token_store_path)
    if config.auth_token.strip():
        token_store.import_legacy(config.auth_token, device_name="Legacy Android device")
        config.auth_token = ""
        config.token_store_file = str(token_store_path)
        save_config(config_path.expanduser().resolve(), config)
    return token_store


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    if args.command == "init":
        return init_command(args)
    if args.command == "serve":
        return serve_command(args)
    if args.command == "pairing":
        return pairing_command(
            args.config,
            args.write,
            args.device_name,
            args.token_expires_days,
            args.scopes,
        )
    if args.command == "phone":
        return queue_phone_files_command(args.config, args.paths)
    if args.command == "configure":
        return configure_command(args.config, args)
    if args.command == "devices":
        return devices_command(args.config, args.action, args.device_id)
    parser.error("unknown command")
    return 2


def phone_main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="phone")
    add_phone_arguments(parser)
    args = parser.parse_args(argv)
    return queue_phone_files_command(args.config, args.paths)
