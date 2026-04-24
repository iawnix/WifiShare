from __future__ import annotations

from dataclasses import dataclass
import hashlib
import hmac
import json
import re
from pathlib import Path
import shutil
import ssl
import threading
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

from .config import ServerConfig
from .files import normalize_sha256, sanitize_filename, unique_destination
from .outbox import acknowledge_phone_transfer, get_phone_transfer, next_phone_transfer


_OUTBOX_CONTENT_PATH = re.compile(r"^/api/v1/outbox/([A-Za-z0-9-]+)/content$")
_OUTBOX_ACK_PATH = re.compile(r"^/api/v1/outbox/([A-Za-z0-9-]+)/ack$")


@dataclass(slots=True)
class UploadRecord:
    filename: str
    digest: str
    size: int
    path: Path
    device_name: str


class WifiShareServer(ThreadingHTTPServer):
    def __init__(
        self,
        config: ServerConfig,
        handler_type: type[BaseHTTPRequestHandler],
        ssl_context: ssl.SSLContext,
    ):
        super().__init__((config.listen_host, config.listen_port), handler_type)
        self.config = config
        self.ssl_context = ssl_context
        self.last_upload: UploadRecord | None = None
        self._upload_lock = threading.Lock()

    def get_request(self):  # type: ignore[override]
        raw_socket, client_address = super().get_request()
        try:
            tls_socket = self.ssl_context.wrap_socket(raw_socket, server_side=True)
        except Exception:
            raw_socket.close()
            raise
        return tls_socket, client_address


class UploadRequestHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "WifiShare/0.3"

    @property
    def app(self) -> WifiShareServer:
        return self.server  # type: ignore[return-value]

    def handle(self) -> None:
        try:
            super().handle()
        except (ConnectionError, ssl.SSLError, OSError):
            # Mobile clients commonly close sockets immediately after an error response.
            # Treat that as a disconnected client, not as a server-side traceback.
            self.close_connection = True

    def do_GET(self) -> None:
        path = urlsplit(self.path).path
        if path == "/api/v1/ping":
            self._write_json(
                HTTPStatus.OK,
                {
                    "server_name": self.app.config.server_name,
                    "base_url": self.app.config.base_url,
                    "tls": "tls1.3+",
                },
            )
            return

        if path == "/api/v1/outbox/next":
            self._handle_outbox_next()
            return

        content_match = _OUTBOX_CONTENT_PATH.fullmatch(path)
        if content_match:
            self._handle_outbox_content(content_match.group(1))
            return

        self._write_json(HTTPStatus.NOT_FOUND, {"error": "not_found"})

    def do_POST(self) -> None:
        path = urlsplit(self.path).path
        if path == "/api/v1/uploads":
            self._handle_upload()
            return

        ack_match = _OUTBOX_ACK_PATH.fullmatch(path)
        if ack_match:
            self._handle_outbox_ack(ack_match.group(1))
            return

        self._write_json(HTTPStatus.NOT_FOUND, {"error": "not_found"})

    def _handle_upload(self) -> None:
        if not self._is_authorized():
            self._write_unauthorized()
            return

        content_length_header = self.headers.get("Content-Length")
        if content_length_header is None:
            self._write_json(
                HTTPStatus.LENGTH_REQUIRED,
                {"error": "missing_content_length"},
                close=True,
            )
            return

        try:
            content_length = int(content_length_header)
        except ValueError:
            self._write_json(
                HTTPStatus.BAD_REQUEST,
                {"error": "invalid_content_length"},
                close=True,
            )
            return

        if content_length < 0:
            self._write_json(
                HTTPStatus.BAD_REQUEST,
                {"error": "negative_content_length"},
                close=True,
            )
            return
        if content_length > self.app.config.upload_limit_bytes:
            self._write_json(
                HTTPStatus.REQUEST_ENTITY_TOO_LARGE,
                {"error": "file_too_large"},
                close=True,
            )
            return

        try:
            claimed_digest = normalize_sha256(self.headers.get("X-Content-SHA256", ""))
        except ValueError:
            self._write_json(HTTPStatus.BAD_REQUEST, {"error": "invalid_sha256"}, close=True)
            return

        raw_name = self.headers.get("X-File-Name", "upload.bin")
        filename = sanitize_filename(raw_name)
        device_name = self.headers.get("X-Device-Name", "unknown").strip() or "unknown"

        upload_dir = Path(self.app.config.upload_dir)
        upload_dir.mkdir(parents=True, exist_ok=True)
        temp_path = upload_dir / f".{filename}.{threading.get_ident()}.part"
        digest = hashlib.sha256()

        try:
            with temp_path.open("wb") as handle:
                remaining = content_length
                while remaining > 0:
                    chunk = self.rfile.read(min(1024 * 1024, remaining))
                    if not chunk:
                        raise ConnectionError("client disconnected before upload completed")
                    handle.write(chunk)
                    digest.update(chunk)
                    remaining -= len(chunk)
        except Exception as exc:
            temp_path.unlink(missing_ok=True)
            self._write_json(HTTPStatus.BAD_REQUEST, {"error": "upload_failed", "detail": str(exc)})
            return

        actual_digest = digest.hexdigest()
        if actual_digest != claimed_digest:
            temp_path.unlink(missing_ok=True)
            self._write_json(HTTPStatus.BAD_REQUEST, {"error": "sha256_mismatch"})
            return

        with self.app._upload_lock:
            final_path = unique_destination(upload_dir, filename)
            temp_path.replace(final_path)
            self.app.last_upload = UploadRecord(
                filename=final_path.name,
                digest=actual_digest,
                size=content_length,
                path=final_path,
                device_name=device_name,
            )

        self._write_json(
            HTTPStatus.CREATED,
            {
                "stored_as": final_path.name,
                "sha256": actual_digest,
                "size": content_length,
            },
        )

    def _handle_outbox_next(self) -> None:
        if not self._is_authorized():
            self._write_unauthorized()
            return

        transfer = next_phone_transfer(self.app.config.phone_queue_path)
        if transfer is None:
            self._write_empty(HTTPStatus.NO_CONTENT)
            return

        self._write_json(
            HTTPStatus.OK,
            {
                "transfer_id": transfer.transfer_id,
                "filename": transfer.filename,
                "sha256": transfer.sha256,
                "size": transfer.size,
                "queued_at": transfer.queued_at,
                "content_path": f"/api/v1/outbox/{transfer.transfer_id}/content",
                "ack_path": f"/api/v1/outbox/{transfer.transfer_id}/ack",
            },
        )

    def _handle_outbox_content(self, transfer_id: str) -> None:
        if not self._is_authorized():
            self._write_unauthorized()
            return

        transfer = get_phone_transfer(self.app.config.phone_queue_path, transfer_id)
        if transfer is None:
            self._write_json(HTTPStatus.NOT_FOUND, {"error": "not_found"})
            return

        self.send_response(HTTPStatus.OK)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Length", str(transfer.size))
        self.send_header("X-File-Name", transfer.filename)
        self.send_header("X-Content-SHA256", transfer.sha256)
        self.end_headers()
        with transfer.payload_path.open("rb") as handle:
            shutil.copyfileobj(handle, self.wfile)

    def _handle_outbox_ack(self, transfer_id: str) -> None:
        if not self._is_authorized():
            self._write_unauthorized()
            return

        if not acknowledge_phone_transfer(self.app.config.phone_queue_path, transfer_id):
            self._write_json(HTTPStatus.NOT_FOUND, {"error": "not_found"})
            return

        self._write_json(HTTPStatus.OK, {"status": "acknowledged", "transfer_id": transfer_id})

    def log_message(self, format: str, *args: object) -> None:
        message = "%s - - [%s] %s\n" % (
            self.address_string(),
            self.log_date_time_string(),
            format % args,
        )
        print(message, end="")

    def _is_authorized(self) -> bool:
        authorization = self.headers.get("Authorization", "")
        prefix = "Bearer "
        if not authorization.startswith(prefix):
            return False
        provided = authorization[len(prefix) :].strip()
        return hmac.compare_digest(provided, self.app.config.auth_token)

    def _write_unauthorized(self) -> None:
        self.close_connection = True
        self.send_response(HTTPStatus.UNAUTHORIZED)
        self.send_header("WWW-Authenticate", "Bearer")
        self.send_header("Connection", "close")
        self.send_header("Content-Length", "0")
        self.end_headers()

    def _write_json(
        self,
        status: HTTPStatus,
        payload: dict[str, object],
        *,
        close: bool = False,
    ) -> None:
        body = json.dumps(payload, ensure_ascii=True).encode("utf-8")
        if close:
            self.close_connection = True
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        if close:
            self.send_header("Connection", "close")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _write_empty(self, status: HTTPStatus) -> None:
        self.send_response(status)
        self.send_header("Content-Length", "0")
        self.end_headers()


def create_server(config: ServerConfig) -> WifiShareServer:
    config.validate()
    ssl_context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ssl_context.minimum_version = ssl.TLSVersion.TLSv1_3
    ssl_context.load_cert_chain(config.cert_file, config.key_file)

    return WifiShareServer(config, UploadRequestHandler, ssl_context)
