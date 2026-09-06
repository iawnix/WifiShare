from __future__ import annotations

import hashlib
import contextlib
from http import HTTPStatus
from http.client import HTTPSConnection
import io
import json
from pathlib import Path
import stat
import ssl
import tempfile
import threading
import time
from types import SimpleNamespace
import unittest
from unittest.mock import patch
from urllib import error, request

from lss_server.certs import generate_self_signed_certificate
from lss_server.config import ServerConfig, load_config, save_config
from lss_server.files import normalize_sha256, sanitize_filename
from lss_server.httpd import UploadRequestHandler, create_server
from lss_server.main import main, phone_main
from lss_server.outbox import next_phone_transfer, queue_phone_file
from lss_server.pairing import build_pairing_payload, build_pairing_uri
from lss_server.tokens import (
    DeviceTokenStore,
    SCOPE_OUTBOX_ACK,
    SCOPE_OUTBOX_READ,
    SCOPE_UPLOAD,
)


def _free_port() -> int:
    import socket

    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return int(sock.getsockname()[1])


def _urlopen_direct(target, *, context):
    opener = request.build_opener(
        request.ProxyHandler({}),
        request.HTTPSHandler(context=context),
    )
    return opener.open(target)


class ServerHarness:
    def __init__(self, config: ServerConfig):
        self.server = create_server(config)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)

    def __enter__(self) -> "ServerHarness":
        self.thread.start()
        time.sleep(0.1)
        return self

    def __exit__(self, exc_type, exc, tb) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)


def _test_server_config(tmp_dir: Path, **overrides: object) -> ServerConfig:
    cert_path = tmp_dir / "server.crt"
    key_path = tmp_dir / "server.key"
    generate_self_signed_certificate(cert_path, key_path, "test-server", "127.0.0.1")
    values: dict[str, object] = {
        "server_name": "test-server",
        "listen_host": "127.0.0.1",
        "listen_port": _free_port(),
        "advertise_host": "127.0.0.1",
        "auth_token": "test-token-value-1234567890",
        "cert_file": str(cert_path),
        "key_file": str(key_path),
        "upload_dir": str(tmp_dir / "uploads"),
        "phone_queue_dir": str(tmp_dir / "phone-outbox"),
        "max_upload_mb": 5,
        "min_free_mb": 0,
    }
    values.update(overrides)
    return ServerConfig(**values)


def _raw_https_request(
    port: int,
    method: str,
    path: str,
    headers: dict[str, str],
    body: bytes = b"",
) -> tuple[int, bytes]:
    connection = HTTPSConnection(
        "127.0.0.1",
        port,
        timeout=5,
        context=ssl._create_unverified_context(),
    )
    try:
        connection.putrequest(method, path)
        for name, value in headers.items():
            connection.putheader(name, value)
        connection.endheaders(body)
        response = connection.getresponse()
        return response.status, response.read()
    finally:
        connection.close()


class HelperTests(unittest.TestCase):
    def test_helpers(self) -> None:
        self.assertEqual(sanitize_filename("../../report.pdf"), "report.pdf")
        self.assertEqual(sanitize_filename("a:b?.txt"), "a_b_.txt")
        self.assertLessEqual(len(sanitize_filename("a" * 300 + ".txt")), 180)
        self.assertEqual(normalize_sha256("AA:BB" + "0" * 60), "aabb" + "0" * 60)

    def test_pairing_uri_encodes_receiver_config(self) -> None:
        config = ServerConfig(
            server_name="manjaro host",
            listen_host="0.0.0.0",
            listen_port=8443,
            advertise_host="192.168.1.23",
            auth_token="token with unsafe chars +/=",
            cert_file="server.crt",
            key_file="server.key",
            upload_dir="uploads",
        )
        payload = build_pairing_payload(config, "a" * 64)
        pairing_uri = build_pairing_uri(payload)

        self.assertTrue(pairing_uri.startswith("lss://pair?"))
        self.assertIn("server_name=manjaro+host", pairing_uri)
        self.assertIn("base_url=https%3A%2F%2F192.168.1.23%3A8443", pairing_uri)
        self.assertIn("auth_token=token+with+unsafe+chars+%2B%2F%3D", pairing_uri)
        self.assertIn("certificate_sha256=" + "a" * 64, pairing_uri)

    def test_load_config_resolves_default_state_paths_from_config_location(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir_name:
            server_dir = Path(tmp_dir_name)
            state_dir = server_dir / "state"
            state_dir.mkdir()
            config_path = state_dir / "config.json"
            config_path.write_text(
                json.dumps(
                    {
                        "server_name": "test-server",
                        "listen_host": "127.0.0.1",
                        "listen_port": 8443,
                        "advertise_host": "127.0.0.1",
                        "auth_token": "test-token-value-1234567890",
                        "cert_file": "state/server.crt",
                        "key_file": "state/server.key",
                        "upload_dir": "state/uploads",
                        "phone_queue_dir": "state/phone-outbox",
                        "max_upload_mb": 5,
                    },
                    indent=2,
                    sort_keys=True,
                ),
                encoding="utf-8",
            )

            with patch.dict("os.environ", {}, clear=True):
                config = load_config(config_path)

            self.assertEqual(Path(config.cert_file), state_dir / "server.crt")
            self.assertEqual(Path(config.key_file), state_dir / "server.key")
            self.assertEqual(Path(config.upload_dir), state_dir / "uploads")
            self.assertEqual(Path(config.phone_queue_dir), state_dir / "phone-outbox")

    def test_load_config_allows_runtime_path_env_overrides(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir_name:
            root_dir = Path(tmp_dir_name)
            state_dir = root_dir / "state"
            state_dir.mkdir()
            download_dir = root_dir / "custom-downloads"
            queue_dir = root_dir / "custom-phone-queue"
            config_path = state_dir / "config.json"
            config_path.write_text(
                json.dumps(
                    {
                        "server_name": "test-server",
                        "listen_host": "127.0.0.1",
                        "listen_port": 8443,
                        "advertise_host": "127.0.0.1",
                        "auth_token": "test-token-value-1234567890",
                        "cert_file": "state/server.crt",
                        "key_file": "state/server.key",
                        "upload_dir": "state/uploads",
                        "phone_queue_dir": "state/phone-outbox",
                        "max_upload_mb": 5,
                    },
                    indent=2,
                    sort_keys=True,
                ),
                encoding="utf-8",
            )

            with patch.dict(
                "os.environ",
                {
                    "LAN_SECURE_SHARE_DOWNLOAD_DIR": str(download_dir),
                    "LAN_SECURE_SHARE_PHONE_QUEUE_DIR": str(queue_dir),
                },
                clear=True,
            ):
                config = load_config(config_path)

            self.assertEqual(Path(config.upload_dir), download_dir)
            self.assertEqual(Path(config.phone_queue_dir), queue_dir)

    def test_init_can_print_pairing_without_writing_pairing_files(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir_name:
            state_dir = Path(tmp_dir_name) / "state"
            upload_dir = Path(tmp_dir_name) / "uploads"
            queue_dir = Path(tmp_dir_name) / "phone-outbox"

            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                result = main(
                    [
                        "init",
                        "--state-dir",
                        str(state_dir),
                        "--server-name",
                        "test-server",
                        "--advertise-host",
                        "127.0.0.1",
                        "--upload-dir",
                        str(upload_dir),
                        "--phone-queue-dir",
                        str(queue_dir),
                        "--no-write-pairing",
                    ]
                )

            self.assertEqual(result, 0)
            self.assertIn("Pairing files:     not written", output.getvalue())
            self.assertIn("Pairing URI:", output.getvalue())
            self.assertTrue((state_dir / "config.json").exists())
            self.assertTrue((state_dir / "server.crt").exists())
            self.assertTrue((state_dir / "device-tokens.json").exists())
            self.assertFalse((state_dir / "pairing.json").exists())
            self.assertFalse((state_dir / "pairing-uri.txt").exists())
            self.assertEqual(state_dir.stat().st_mode & 0o777, 0o700)
            self.assertEqual((state_dir / "config.json").stat().st_mode & 0o777, 0o600)
            self.assertEqual((state_dir / "server.key").stat().st_mode & 0o777, 0o600)
            self.assertEqual((state_dir / "device-tokens.json").stat().st_mode & 0o777, 0o600)

    def test_init_validates_limits_before_replacing_existing_credentials(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir_name:
            state_dir = Path(tmp_dir_name) / "state"
            state_dir.mkdir()
            token_store_path = state_dir / "device-tokens.json"
            token_store_path.write_text("existing credentials\n", encoding="utf-8")

            with self.assertRaisesRegex(ValueError, "max_upload_mb must be positive"):
                main(
                    [
                        "init",
                        "--state-dir",
                        str(state_dir),
                        "--advertise-host",
                        "127.0.0.1",
                        "--max-upload-mb",
                        "0",
                    ]
                )

            self.assertEqual(token_store_path.read_text(encoding="utf-8"), "existing credentials\n")
            self.assertFalse((state_dir / "server.crt").exists())

    def test_device_tokens_are_hashed_scoped_and_revocable(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir_name:
            store_path = Path(tmp_dir_name) / "device-tokens.json"
            store = DeviceTokenStore(store_path)
            issued = store.issue("Test phone", scopes={SCOPE_UPLOAD}, expires_days=1)

            self.assertNotIn(issued.token, store_path.read_text(encoding="utf-8"))
            self.assertEqual(store_path.stat().st_mode & 0o777, 0o600)
            self.assertTrue(store.authorize(issued.token, SCOPE_UPLOAD))
            self.assertFalse(store.authorize(issued.token, SCOPE_OUTBOX_READ))
            self.assertTrue(store.revoke(issued.device_id))
            self.assertFalse(store.authorize(issued.token, SCOPE_UPLOAD))

    def test_configure_changes_limit_without_rotating_legacy_token(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir_name:
            config_path = Path(tmp_dir_name) / "state" / "config.json"
            config = ServerConfig(
                server_name="test-server",
                listen_host="127.0.0.1",
                listen_port=8443,
                advertise_host="127.0.0.1",
                auth_token="test-token-value-1234567890",
                cert_file="server.crt",
                key_file="server.key",
                upload_dir="uploads",
            )
            save_config(config_path, config)

            self.assertEqual(
                main(["configure", "--config", str(config_path), "--max-upload-mb", "4096"]),
                0,
            )
            updated = load_config(config_path)
            self.assertEqual(updated.max_upload_mb, 4096)
            self.assertEqual(updated.auth_token, config.auth_token)

    def test_outbox_rejects_a_file_above_its_limit(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir_name:
            root = Path(tmp_dir_name)
            source = root / "large.bin"
            source.write_bytes(b"12")
            with self.assertRaisesRegex(ValueError, "outbox limit"):
                queue_phone_file(root / "queue", source, max_file_bytes=1)

    def test_outbox_files_and_directories_are_private(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir_name:
            root = Path(tmp_dir_name)
            source = root / "private.txt"
            source.write_bytes(b"private")
            queue_dir = root / "queue"

            queued = queue_phone_file(queue_dir, source)
            pending_dir = queue_dir / "pending"
            self.assertEqual(stat.S_IMODE(queue_dir.stat().st_mode), 0o700)
            self.assertEqual(stat.S_IMODE(pending_dir.stat().st_mode), 0o700)
            self.assertEqual(stat.S_IMODE(queued.payload_path.stat().st_mode), 0o600)
            self.assertEqual(
                stat.S_IMODE((pending_dir / f"{queued.transfer_id}.json").stat().st_mode),
                0o600,
            )

            leased = next_phone_transfer(queue_dir)
            self.assertIsNotNone(leased)
            assert leased is not None
            self.assertEqual(stat.S_IMODE((queue_dir / "inflight").stat().st_mode), 0o700)
            self.assertEqual(stat.S_IMODE(leased.payload_path.stat().st_mode), 0o600)
            self.assertEqual(
                stat.S_IMODE((queue_dir / "inflight" / f"{leased.transfer_id}.json").stat().st_mode),
                0o600,
            )

    def test_incomplete_upload_removes_temporary_file(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir_name:
            upload_dir = Path(tmp_dir_name) / "uploads"
            upload_dir.mkdir()
            responses: list[tuple[HTTPStatus, dict[str, object], bool]] = []
            handler = object.__new__(UploadRequestHandler)
            handler.headers = {"X-File-Name": "partial.bin"}
            handler.rfile = io.BytesIO(b"short")
            handler.server = SimpleNamespace(
                config=SimpleNamespace(upload_dir=str(upload_dir)),
                _upload_lock=threading.Lock(),
                last_upload=None,
            )
            handler._write_json = lambda status, payload, close=False: responses.append(
                (status, payload, close)
            )

            handler._receive_upload(10, hashlib.sha256(b"short").hexdigest())

            self.assertEqual(responses, [(HTTPStatus.BAD_REQUEST, {"error": "upload_incomplete"}, True)])
            self.assertEqual(list(upload_dir.iterdir()), [])


class UploadServerTests(unittest.TestCase):
    def test_device_token_scopes_are_enforced_by_http_endpoints(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir_name:
            tmp_dir = Path(tmp_dir_name)
            token_store_path = tmp_dir / "device-tokens.json"
            token_store = DeviceTokenStore(token_store_path)
            token_store.reset()
            upload_token = token_store.issue("upload-only", scopes={SCOPE_UPLOAD})
            read_token = token_store.issue("read-only", scopes={SCOPE_OUTBOX_READ})
            ack_token = token_store.issue("ack-only", scopes={SCOPE_OUTBOX_ACK})
            config = _test_server_config(
                tmp_dir,
                auth_token="",
                token_store_file=str(token_store_path),
            )
            source_path = tmp_dir / "queued.txt"
            source_path.write_bytes(b"queued payload")
            queue_phone_file(config.phone_queue_path, source_path)
            payload = b"uploaded payload"

            with ServerHarness(config):
                status, _ = _raw_https_request(
                    config.listen_port,
                    "POST",
                    "/api/v1/uploads",
                    {
                        "Authorization": f"Bearer {upload_token.token}",
                        "Content-Length": str(len(payload)),
                        "X-File-Name": "uploaded.txt",
                        "X-Content-SHA256": hashlib.sha256(payload).hexdigest(),
                    },
                    payload,
                )
                self.assertEqual(status, 201)

                status, _ = _raw_https_request(
                    config.listen_port,
                    "GET",
                    "/api/v1/outbox/next",
                    {"Authorization": f"Bearer {upload_token.token}"},
                )
                self.assertEqual(status, 401)

                status, body = _raw_https_request(
                    config.listen_port,
                    "GET",
                    "/api/v1/outbox/next",
                    {"Authorization": f"Bearer {read_token.token}"},
                )
                self.assertEqual(status, 200)
                transfer_id = json.loads(body.decode("utf-8"))["transfer_id"]

                status, _ = _raw_https_request(
                    config.listen_port,
                    "POST",
                    f"/api/v1/outbox/{transfer_id}/ack",
                    {
                        "Authorization": f"Bearer {read_token.token}",
                        "Content-Length": "0",
                    },
                )
                self.assertEqual(status, 401)

                status, _ = _raw_https_request(
                    config.listen_port,
                    "POST",
                    f"/api/v1/outbox/{transfer_id}/ack",
                    {
                        "Authorization": f"Bearer {ack_token.token}",
                        "Content-Length": "0",
                    },
                )
                self.assertEqual(status, 200)

    def test_upload_above_file_limit_returns_413(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir_name:
            tmp_dir = Path(tmp_dir_name)
            config = _test_server_config(tmp_dir, max_upload_mb=1)

            with ServerHarness(config):
                status, body = _raw_https_request(
                    config.listen_port,
                    "POST",
                    "/api/v1/uploads",
                    {
                        "Authorization": f"Bearer {config.auth_token}",
                        "Content-Length": str(1024 * 1024 + 1),
                        "X-Content-SHA256": hashlib.sha256(b"").hexdigest(),
                    },
                )

            self.assertEqual(status, 413)
            self.assertEqual(json.loads(body.decode("utf-8"))["error"], "file_too_large")
            self.assertFalse(Path(config.upload_dir).exists())

    def test_upload_above_storage_limit_returns_507(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir_name:
            tmp_dir = Path(tmp_dir_name)
            config = _test_server_config(tmp_dir, max_storage_mb=1)
            upload_dir = Path(config.upload_dir)
            upload_dir.mkdir()
            (upload_dir / "existing.bin").write_bytes(b"x" * (1024 * 1024))
            payload = b"y"

            with ServerHarness(config):
                status, body = _raw_https_request(
                    config.listen_port,
                    "POST",
                    "/api/v1/uploads",
                    {
                        "Authorization": f"Bearer {config.auth_token}",
                        "Content-Length": str(len(payload)),
                        "X-Content-SHA256": hashlib.sha256(payload).hexdigest(),
                    },
                    payload,
                )

            self.assertEqual(status, 507)
            self.assertEqual(json.loads(body.decode("utf-8"))["error"], "storage_limit_reached")

    def test_rate_limit_returns_429(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir_name:
            config = _test_server_config(Path(tmp_dir_name), requests_per_minute=1)

            with ServerHarness(config):
                first_status, _ = _raw_https_request(
                    config.listen_port,
                    "GET",
                    "/api/v1/ping",
                    {},
                )
                second_status, body = _raw_https_request(
                    config.listen_port,
                    "GET",
                    "/api/v1/ping",
                    {},
                )

            self.assertEqual(first_status, 200)
            self.assertEqual(second_status, 429)
            self.assertEqual(json.loads(body.decode("utf-8"))["error"], "rate_limited")

    def test_corrupt_token_store_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir_name:
            tmp_dir = Path(tmp_dir_name)
            token_store_path = tmp_dir / "device-tokens.json"
            token_store = DeviceTokenStore(token_store_path)
            token_store.reset()
            issued = token_store.issue("test phone")
            config = _test_server_config(
                tmp_dir,
                auth_token="",
                token_store_file=str(token_store_path),
            )

            with ServerHarness(config):
                token_store_path.write_text('{"version": 1, "devices": [42]}\n', encoding="utf-8")
                status, _ = _raw_https_request(
                    config.listen_port,
                    "GET",
                    "/api/v1/outbox/next",
                    {"Authorization": f"Bearer {issued.token}"},
                )
                self.assertEqual(status, 401)

            with self.assertRaisesRegex(ValueError, "invalid device token store"):
                create_server(config)

    def test_upload_roundtrip(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir_name:
            tmp_dir = Path(tmp_dir_name)
            cert_path = tmp_dir / "server.crt"
            key_path = tmp_dir / "server.key"
            upload_dir = tmp_dir / "uploads"
            port = _free_port()
            generate_self_signed_certificate(cert_path, key_path, "test-server", "127.0.0.1")

            config = ServerConfig(
                server_name="test-server",
                listen_host="127.0.0.1",
                listen_port=port,
                advertise_host="127.0.0.1",
                auth_token="test-token-value-1234567890",
                cert_file=str(cert_path),
                key_file=str(key_path),
                upload_dir=str(upload_dir),
                max_upload_mb=5,
            )

            payload = b"hello from android"
            digest = hashlib.sha256(payload).hexdigest()
            context = ssl._create_unverified_context()

            with ServerHarness(config):
                ping_response = _urlopen_direct(
                    f"https://127.0.0.1:{port}/api/v1/ping",
                    context=context,
                )
                ping_payload = json.loads(ping_response.read().decode("utf-8"))
                self.assertEqual(ping_payload["status"], "ok")
                self.assertNotIn("server_name", ping_payload)
                self.assertNotIn("base_url", ping_payload)

                upload_request = request.Request(
                    f"https://127.0.0.1:{port}/api/v1/uploads",
                    data=payload,
                    method="POST",
                    headers={
                        "Authorization": f"Bearer {config.auth_token}",
                        "Content-Type": "application/octet-stream",
                        "X-File-Name": "example.txt",
                        "X-Content-SHA256": digest,
                        "X-Device-Name": "unit-test",
                    },
                )
                response = _urlopen_direct(upload_request, context=context)
                response_payload = json.loads(response.read().decode("utf-8"))
                self.assertEqual(response.status, 201)
                self.assertEqual(response_payload["stored_as"], "example.txt")
                self.assertEqual((upload_dir / "example.txt").read_bytes(), payload)

    def test_upload_requires_token(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir_name:
            tmp_dir = Path(tmp_dir_name)
            cert_path = tmp_dir / "server.crt"
            key_path = tmp_dir / "server.key"
            port = _free_port()
            generate_self_signed_certificate(cert_path, key_path, "test-server", "127.0.0.1")

            config = ServerConfig(
                server_name="test-server",
                listen_host="127.0.0.1",
                listen_port=port,
                advertise_host="127.0.0.1",
                auth_token="test-token-value-1234567890",
                cert_file=str(cert_path),
                key_file=str(key_path),
                upload_dir=str(tmp_dir / "uploads"),
                max_upload_mb=5,
            )

            payload = b"unauthorized"
            digest = hashlib.sha256(payload).hexdigest()
            context = ssl._create_unverified_context()

            with ServerHarness(config):
                upload_request = request.Request(
                    f"https://127.0.0.1:{port}/api/v1/uploads",
                    data=payload,
                    method="POST",
                    headers={
                        "Content-Type": "application/octet-stream",
                        "X-File-Name": "example.txt",
                        "X-Content-SHA256": digest,
                    },
                )
                with self.assertRaises(error.HTTPError) as exc_ctx:
                    _urlopen_direct(upload_request, context=context)
                self.assertEqual(exc_ctx.exception.code, 401)
                self.assertEqual(exc_ctx.exception.headers.get("Connection"), "close")
                exc_ctx.exception.close()

    def test_phone_queue_roundtrip(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir_name:
            tmp_dir = Path(tmp_dir_name)
            cert_path = tmp_dir / "server.crt"
            key_path = tmp_dir / "server.key"
            config_path = tmp_dir / "config.json"
            source_path = tmp_dir / "from-linux.txt"
            source_path.write_bytes(b"hello phone")
            port = _free_port()
            generate_self_signed_certificate(cert_path, key_path, "test-server", "127.0.0.1")

            config = ServerConfig(
                server_name="test-server",
                listen_host="127.0.0.1",
                listen_port=port,
                advertise_host="127.0.0.1",
                auth_token="test-token-value-1234567890",
                cert_file=str(cert_path),
                key_file=str(key_path),
                upload_dir=str(tmp_dir / "uploads"),
                phone_queue_dir=str(tmp_dir / "phone-outbox"),
                max_upload_mb=5,
            )
            config_path.write_text(
                json.dumps(
                    {
                        "server_name": config.server_name,
                        "listen_host": config.listen_host,
                        "listen_port": config.listen_port,
                        "advertise_host": config.advertise_host,
                        "auth_token": config.auth_token,
                        "cert_file": config.cert_file,
                        "key_file": config.key_file,
                        "upload_dir": config.upload_dir,
                        "phone_queue_dir": config.phone_queue_dir,
                        "max_upload_mb": config.max_upload_mb,
                    },
                    indent=2,
                    sort_keys=True,
                )
                + "\n",
                encoding="utf-8",
            )

            self.assertEqual(phone_main([str(source_path), "--config", str(config_path)]), 0)
            context = ssl._create_unverified_context()

            with ServerHarness(config):
                next_request = request.Request(
                    f"https://127.0.0.1:{port}/api/v1/outbox/next",
                    headers={"Authorization": f"Bearer {config.auth_token}"},
                )
                next_response = _urlopen_direct(next_request, context=context)
                next_payload = json.loads(next_response.read().decode("utf-8"))
                self.assertEqual(next_payload["filename"], "from-linux.txt")
                self.assertIn("lease_expires_at", next_payload)

                duplicate_next_response = _urlopen_direct(next_request, context=context)
                self.assertEqual(duplicate_next_response.status, 204)

                transfer_id = next_payload["transfer_id"]
                content_request = request.Request(
                    f"https://127.0.0.1:{port}/api/v1/outbox/{transfer_id}/content",
                    headers={"Authorization": f"Bearer {config.auth_token}"},
                )
                content_response = _urlopen_direct(content_request, context=context)
                self.assertEqual(content_response.read(), b"hello phone")

                ack_request = request.Request(
                    f"https://127.0.0.1:{port}/api/v1/outbox/{transfer_id}/ack",
                    method="POST",
                    headers={"Authorization": f"Bearer {config.auth_token}"},
                )
                ack_response = _urlopen_direct(ack_request, context=context)
                ack_payload = json.loads(ack_response.read().decode("utf-8"))
                self.assertEqual(ack_payload["status"], "acknowledged")

                empty_request = request.Request(
                    f"https://127.0.0.1:{port}/api/v1/outbox/next",
                    headers={"Authorization": f"Bearer {config.auth_token}"},
                )
                empty_response = _urlopen_direct(empty_request, context=context)
                self.assertEqual(empty_response.status, 204)

    def test_phone_queue_recovers_expired_lease(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir_name:
            tmp_dir = Path(tmp_dir_name)
            queue_dir = tmp_dir / "phone-outbox"
            source_path = tmp_dir / "from-linux.txt"
            source_path.write_bytes(b"hello phone")

            queued = queue_phone_file(queue_dir, source_path)
            leased = next_phone_transfer(queue_dir)
            self.assertIsNotNone(leased)
            self.assertEqual(leased.transfer_id, queued.transfer_id)
            self.assertIsNone(next_phone_transfer(queue_dir))

            inflight_meta = queue_dir / "inflight" / f"{queued.transfer_id}.json"
            payload = json.loads(inflight_meta.read_text(encoding="utf-8"))
            payload["lease_expires_at"] = "2000-01-01T00:00:00Z"
            inflight_meta.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")

            recovered = next_phone_transfer(queue_dir)
            self.assertIsNotNone(recovered)
            self.assertEqual(recovered.transfer_id, queued.transfer_id)


if __name__ == "__main__":
    unittest.main()
