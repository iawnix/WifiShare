from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
import hashlib
import hmac
import json
from pathlib import Path
import re
import secrets
import threading

from .security import atomic_write_private_text


SCOPE_UPLOAD = "upload"
SCOPE_OUTBOX_READ = "outbox.read"
SCOPE_OUTBOX_ACK = "outbox.ack"
ALL_SCOPES = frozenset({SCOPE_UPLOAD, SCOPE_OUTBOX_READ, SCOPE_OUTBOX_ACK})
_DEVICE_ID_PATTERN = re.compile(r"^[A-Za-z0-9_-]{1,64}$")
_SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")


@dataclass(frozen=True, slots=True)
class IssuedToken:
    device_id: str
    token: str
    device_name: str
    scopes: tuple[str, ...]
    expires_at: str


class DeviceTokenStore:
    def __init__(self, path: Path):
        self.path = path
        self._lock = threading.Lock()

    def reset(self) -> None:
        with self._lock:
            self._save({"version": 1, "devices": []})

    def validate(self) -> None:
        with self._lock:
            self._load()

    def issue(
        self,
        device_name: str,
        *,
        scopes: set[str] | frozenset[str] = ALL_SCOPES,
        expires_days: int = 365,
    ) -> IssuedToken:
        normalized_scopes = _normalize_scopes(scopes)
        validate_token_expiry_days(expires_days)

        now = datetime.now(timezone.utc).replace(microsecond=0)
        token = "wfs_" + secrets.token_urlsafe(32)
        issued = IssuedToken(
            device_id=secrets.token_hex(8),
            token=token,
            device_name=_normalize_device_name(device_name),
            scopes=tuple(sorted(normalized_scopes)),
            expires_at=_format_utc(now + timedelta(days=expires_days)),
        )
        record = {
            "device_id": issued.device_id,
            "device_name": issued.device_name,
            "token_sha256": _token_digest(token),
            "scopes": list(issued.scopes),
            "created_at": _format_utc(now),
            "expires_at": issued.expires_at,
            "revoked_at": "",
        }
        with self._lock:
            payload = self._load()
            payload["devices"].append(record)
            self._save(payload)
        return issued

    def import_legacy(self, token: str, *, device_name: str = "Legacy device") -> str:
        normalized_token = token.strip()
        if len(normalized_token) < 24:
            raise ValueError("legacy token is too short")
        digest = _token_digest(normalized_token)
        with self._lock:
            payload = self._load()
            for record in payload["devices"]:
                if hmac.compare_digest(str(record.get("token_sha256", "")), digest):
                    return str(record["device_id"])

            device_id = secrets.token_hex(8)
            payload["devices"].append(
                {
                    "device_id": device_id,
                    "device_name": _normalize_device_name(device_name),
                    "token_sha256": digest,
                    "scopes": sorted(ALL_SCOPES),
                    "created_at": _format_utc(datetime.now(timezone.utc).replace(microsecond=0)),
                    "expires_at": "",
                    "revoked_at": "",
                }
            )
            self._save(payload)
            return device_id

    def authorize(self, token: str, required_scope: str) -> bool:
        if required_scope not in ALL_SCOPES or not token:
            return False
        digest = _token_digest(token)
        now = datetime.now(timezone.utc)
        payload = self._load()
        for record in payload["devices"]:
            stored_digest = str(record.get("token_sha256", ""))
            if not hmac.compare_digest(stored_digest, digest):
                continue
            if record.get("revoked_at"):
                return False
            if _is_expired(str(record.get("expires_at", "")), now):
                return False
            return required_scope in set(record.get("scopes", []))
        return False

    def list_devices(self) -> list[dict[str, object]]:
        devices = []
        for record in self._load()["devices"]:
            devices.append(
                {
                    "device_id": str(record.get("device_id", "")),
                    "device_name": str(record.get("device_name", "")),
                    "scopes": list(record.get("scopes", [])),
                    "created_at": str(record.get("created_at", "")),
                    "expires_at": str(record.get("expires_at", "")),
                    "revoked_at": str(record.get("revoked_at", "")),
                }
            )
        return devices

    def revoke(self, device_id: str) -> bool:
        with self._lock:
            payload = self._load()
            for record in payload["devices"]:
                if record.get("device_id") != device_id:
                    continue
                if not record.get("revoked_at"):
                    record["revoked_at"] = _format_utc(
                        datetime.now(timezone.utc).replace(microsecond=0)
                    )
                    self._save(payload)
                return True
        return False

    def _load(self) -> dict[str, object]:
        if not self.path.exists():
            return {"version": 1, "devices": []}
        try:
            payload = json.loads(self.path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            raise ValueError(f"invalid device token store: {self.path}") from exc
        _validate_store_payload(payload, self.path)
        return payload

    def _save(self, payload: dict[str, object]) -> None:
        atomic_write_private_text(
            self.path,
            json.dumps(payload, indent=2, sort_keys=True) + "\n",
        )


def parse_scopes(value: str) -> set[str]:
    scopes = {item.strip() for item in value.split(",") if item.strip()}
    return _normalize_scopes(scopes)


def validate_token_expiry_days(expires_days: int) -> None:
    if expires_days <= 0 or expires_days > 3650:
        raise ValueError("expires_days must be between 1 and 3650")


def _normalize_scopes(scopes: set[str] | frozenset[str]) -> set[str]:
    normalized = set(scopes)
    unknown = normalized - ALL_SCOPES
    if not normalized or unknown:
        expected = ", ".join(sorted(ALL_SCOPES))
        raise ValueError(f"scopes must contain only: {expected}")
    return normalized


def _validate_store_payload(payload: object, path: Path) -> None:
    if not isinstance(payload, dict):
        raise ValueError(f"invalid device token store: {path}")
    devices = payload.get("devices")
    if payload.get("version") != 1 or not isinstance(devices, list):
        raise ValueError(f"unsupported device token store: {path}")

    device_ids: set[str] = set()
    token_digests: set[str] = set()
    for record in devices:
        if not isinstance(record, dict):
            raise ValueError(f"invalid device token store: {path}")
        device_id = record.get("device_id")
        token_digest = record.get("token_sha256")
        scopes = record.get("scopes")
        if not isinstance(device_id, str) or not _DEVICE_ID_PATTERN.fullmatch(device_id):
            raise ValueError(f"invalid device token store: {path}")
        if not isinstance(token_digest, str) or not _SHA256_PATTERN.fullmatch(token_digest):
            raise ValueError(f"invalid device token store: {path}")
        if (
            not isinstance(scopes, list)
            or not scopes
            or any(not isinstance(scope, str) for scope in scopes)
            or not set(scopes).issubset(ALL_SCOPES)
        ):
            raise ValueError(f"invalid device token store: {path}")
        if any(
            not isinstance(record.get(field), str)
            for field in ("device_name", "created_at", "expires_at", "revoked_at")
        ):
            raise ValueError(f"invalid device token store: {path}")
        if device_id in device_ids or token_digest in token_digests:
            raise ValueError(f"invalid device token store: {path}")
        device_ids.add(device_id)
        token_digests.add(token_digest)


def _normalize_device_name(value: str) -> str:
    normalized = " ".join(value.strip().split())
    return (normalized or "Android device")[:80]


def _token_digest(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def _format_utc(value: datetime) -> str:
    return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def _is_expired(value: str, now: datetime) -> bool:
    if not value:
        return False
    try:
        expires_at = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return True
    if expires_at.tzinfo is None:
        expires_at = expires_at.replace(tzinfo=timezone.utc)
    return expires_at <= now
