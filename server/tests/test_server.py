from __future__ import annotations

import hashlib
import json
from pathlib import Path
import ssl
import tempfile
import threading
import time
import unittest
from unittest.mock import patch
from urllib import error, request

from lss_server.certs import generate_self_signed_certificate
from lss_server.config import ServerConfig, load_config
from lss_server.files import normalize_sha256, sanitize_filename
from lss_server.httpd import create_server
from lss_server.main import phone_main
from lss_server.pairing import build_pairing_payload, build_pairing_uri


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


class HelperTests(unittest.TestCase):
    def test_helpers(self) -> None:
        self.assertEqual(sanitize_filename("../../report.pdf"), "report.pdf")
        self.assertEqual(sanitize_filename("a:b?.txt"), "a_b_.txt")
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


class UploadServerTests(unittest.TestCase):
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
                self.assertEqual(ping_payload["server_name"], "test-server")

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


if __name__ == "__main__":
    unittest.main()
