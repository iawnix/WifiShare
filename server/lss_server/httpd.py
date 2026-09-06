from __future__ import annotations

from collections import deque
from dataclasses import dataclass
import hashlib
import hmac
import json
import os
import re
from pathlib import Path
import shutil
import ssl
import tempfile
import threading
import time
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

from .config import ServerConfig
from .files import normalize_sha256, sanitize_filename, unique_destination
from .outbox import acknowledge_phone_transfer, get_phone_transfer, next_phone_transfer
from .security import directory_size
from .tokens import DeviceTokenStore, SCOPE_OUTBOX_ACK, SCOPE_OUTBOX_READ, SCOPE_UPLOAD


_OUTBOX_CONTENT_PATH = re.compile(r"^/api/v1/outbox/([A-Za-z0-9-]+)/content$")
_OUTBOX_ACK_PATH = re.compile(r"^/api/v1/outbox/([A-Za-z0-9-]+)/ack$")


@dataclass(slots=True)
class UploadRecord:
    filename: str
    digest: str
    size: int
    path: Path
    device_name: str


class SlidingWindowRateLimiter:
    def __init__(self, requests_per_minute: int):
        self.limit = requests_per_minute
        self._events: dict[str, deque[float]] = {}
        self._lock = threading.Lock()

    def allow(self, key: str) -> bool:
        now = time.monotonic()
        cutoff = now - 60.0
        with self._lock:
            events = self._events.setdefault(key, deque())
            while events and events[0] <= cutoff:
                events.popleft()
            if len(events) >= self.limit:
                return False
            events.append(now)
            return True


class WifiShareServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(
        self,
        config: ServerConfig,
        handler_type: type[BaseHTTPRequestHandler],
        ssl_context: ssl.SSLContext,
    ):
        token_store = (
            DeviceTokenStore(config.token_store_path)
            if config.token_store_path is not None
            else None
        )
        if token_store is not None:
            if not config.auth_token.strip() and not token_store.path.is_file():
                raise ValueError(f"device token store does not exist: {token_store.path}")
            token_store.validate()
        super().__init__((config.listen_host, config.listen_port), handler_type)
        self.config = config
        self.ssl_context = ssl_context
        self.last_upload: UploadRecord | None = None
        self._upload_lock = threading.Lock()
        self._outbox_lock = threading.Lock()
        self._upload_reservation_lock = threading.Lock()
        self._reserved_upload_bytes = 0
        self._request_slots = threading.BoundedSemaphore(config.max_concurrent_requests)
        self._rate_limiter = SlidingWindowRateLimiter(config.requests_per_minute)
        self._token_store = token_store

    def get_request(self):  # type: ignore[override]
        raw_socket, client_address = super().get_request()
        try:
            raw_socket.settimeout(self.config.tls_handshake_timeout_seconds)
            tls_socket = self.ssl_context.wrap_socket(
                raw_socket,
                server_side=True,
                do_handshake_on_connect=False,
            )
        except Exception:
            raw_socket.close()
            raise
        return tls_socket, client_address

    def process_request(self, request, client_address):  # type: ignore[override]
        if not self._request_slots.acquire(blocking=False):
            self.shutdown_request(request)
            return
        try:
            super().process_request(request, client_address)
        except Exception:
            self._request_slots.release()
            raise

    def process_request_thread(self, request, client_address):  # type: ignore[override]
        try:
            request.settimeout(self.config.tls_handshake_timeout_seconds)
            request.do_handshake()
            request.settimeout(self.config.request_timeout_seconds)
            super().process_request_thread(request, client_address)
        except (ConnectionError, TimeoutError, ssl.SSLError, OSError):
            self.shutdown_request(request)
        finally:
            self._request_slots.release()

    def allow_request_from(self, address: str) -> bool:
        return self._rate_limiter.allow(address)

    def authorize(self, token: str, required_scope: str) -> bool:
        legacy_token = self.config.auth_token.strip()
        if legacy_token and hmac.compare_digest(token, legacy_token):
            return True
        if self._token_store is None:
            return False
        try:
            return self._token_store.authorize(token, required_scope)
        except ValueError:
            return False

    def reserve_upload(self, size: int) -> bool:
        upload_dir = Path(self.config.upload_dir)
        try:
            upload_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
            with self._upload_reservation_lock:
                reserved_after = self._reserved_upload_bytes + size
                if directory_size(upload_dir) + reserved_after > self.config.storage_limit_bytes:
                    return False
                if shutil.disk_usage(upload_dir).free - reserved_after < self.config.minimum_free_bytes:
                    return False
                self._reserved_upload_bytes = reserved_after
                return True
        except OSError:
            return False

    def release_upload(self, size: int) -> None:
        with self._upload_reservation_lock:
            self._reserved_upload_bytes = max(0, self._reserved_upload_bytes - size)


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
        if not self._allow_request():
            return
        path = urlsplit(self.path).path
        if path == "/api/v1/ping":
            self._write_json(
                HTTPStatus.OK,
                {
                    "status": "ok",
                    "protocol": 1,
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
        if not self._allow_request():
            return
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
        if not self._is_authorized(SCOPE_UPLOAD):
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

        if not self.app.reserve_upload(content_length):
            self._write_json(
                HTTPStatus.INSUFFICIENT_STORAGE,
                {"error": "storage_limit_reached"},
                close=True,
            )
            return
        try:
            self._receive_upload(content_length, claimed_digest)
        finally:
            self.app.release_upload(content_length)

    def _receive_upload(self, content_length: int, claimed_digest: str) -> None:
        filename = sanitize_filename(self.headers.get("X-File-Name", "upload.bin"))
        device_name = (self.headers.get("X-Device-Name", "unknown").strip() or "unknown")[:120]
        upload_dir = Path(self.app.config.upload_dir)
        temp_descriptor: int | None = None
        temp_path: Path | None = None

        try:
            try:
                temp_descriptor, temp_name = tempfile.mkstemp(
                    dir=upload_dir,
                    prefix=f".{filename}.",
                    suffix=".part",
                )
                temp_path = Path(temp_name)
                digest = hashlib.sha256()
                output = os.fdopen(temp_descriptor, "wb")
                temp_descriptor = None
                with output as handle:
                    remaining = content_length
                    while remaining > 0:
                        chunk = self.rfile.read(min(1024 * 1024, remaining))
                        if not chunk:
                            raise ConnectionError("client disconnected before upload completed")
                        handle.write(chunk)
                        digest.update(chunk)
                        remaining -= len(chunk)
            except (ConnectionError, TimeoutError):
                self._write_json(HTTPStatus.BAD_REQUEST, {"error": "upload_incomplete"}, close=True)
                return
            except OSError:
                self._write_json(
                    HTTPStatus.INSUFFICIENT_STORAGE,
                    {"error": "storage_write_failed"},
                    close=True,
                )
                return

            actual_digest = digest.hexdigest()
            if actual_digest != claimed_digest:
                self._write_json(HTTPStatus.BAD_REQUEST, {"error": "sha256_mismatch"})
                return

            try:
                with self.app._upload_lock:
                    final_path = unique_destination(upload_dir, filename)
                    temp_path.replace(final_path)
                    temp_path = None
                    self.app.last_upload = UploadRecord(
                        filename=final_path.name,
                        digest=actual_digest,
                        size=content_length,
                        path=final_path,
                        device_name=device_name,
                    )
            except OSError:
                self._write_json(
                    HTTPStatus.INSUFFICIENT_STORAGE,
                    {"error": "storage_write_failed"},
                    close=True,
                )
                return

            self._write_json(
                HTTPStatus.CREATED,
                {
                    "stored_as": final_path.name,
                    "sha256": actual_digest,
                    "size": content_length,
                },
            )
        finally:
            if temp_descriptor is not None:
                try:
                    os.close(temp_descriptor)
                except OSError:
                    pass
            if temp_path is not None:
                try:
                    temp_path.unlink(missing_ok=True)
                except OSError:
                    pass

    def _handle_outbox_next(self) -> None:
        if not self._is_authorized(SCOPE_OUTBOX_READ):
            self._write_unauthorized()
            return

        with self.app._outbox_lock:
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
                "lease_expires_at": transfer.lease_expires_at,
                "content_path": f"/api/v1/outbox/{transfer.transfer_id}/content",
                "ack_path": f"/api/v1/outbox/{transfer.transfer_id}/ack",
            },
        )

    def _handle_outbox_content(self, transfer_id: str) -> None:
        if not self._is_authorized(SCOPE_OUTBOX_READ):
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
        if not self._is_authorized(SCOPE_OUTBOX_ACK):
            self._write_unauthorized()
            return

        with self.app._outbox_lock:
            acknowledged = acknowledge_phone_transfer(self.app.config.phone_queue_path, transfer_id)
        if not acknowledged:
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

    def _allow_request(self) -> bool:
        if self.app.allow_request_from(self.client_address[0]):
            return True
        self._write_json(
            HTTPStatus.TOO_MANY_REQUESTS,
            {"error": "rate_limited"},
            close=True,
        )
        return False

    def _is_authorized(self, required_scope: str) -> bool:
        authorization = self.headers.get("Authorization", "")
        prefix = "Bearer "
        if not authorization.startswith(prefix):
            return False
        provided = authorization[len(prefix) :].strip()
        return self.app.authorize(provided, required_scope)

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
