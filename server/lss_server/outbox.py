from __future__ import annotations

from dataclasses import asdict, dataclass
from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
import secrets

from .files import copy_file_with_sha256, normalize_sha256, sanitize_filename


@dataclass(slots=True)
class PhoneTransfer:
    transfer_id: str
    filename: str
    sha256: str
    size: int
    queued_at: str
    payload_path: Path
    leased_at: str = ""
    lease_expires_at: str = ""

    def to_payload(self) -> dict[str, object]:
        payload = asdict(self)
        payload.pop("payload_path", None)
        if not payload["leased_at"]:
            payload.pop("leased_at", None)
        if not payload["lease_expires_at"]:
            payload.pop("lease_expires_at", None)
        return payload


DEFAULT_LEASE_SECONDS = 300
_PENDING_DIR = "pending"
_INFLIGHT_DIR = "inflight"


def queue_phone_file(queue_dir: Path, source_path: Path) -> PhoneTransfer:
    source = source_path.expanduser()
    if not source.exists():
        raise FileNotFoundError(source)
    if not source.is_file():
        raise ValueError(f"not a regular file: {source}")

    pending_dir = queue_dir / _PENDING_DIR
    pending_dir.mkdir(parents=True, exist_ok=True)

    transfer_id = _new_transfer_id()
    filename = sanitize_filename(source.name)
    temp_payload_path = pending_dir / f".{transfer_id}.payload.part"
    final_payload_path = pending_dir / f"{transfer_id}.payload"
    meta_path = pending_dir / f"{transfer_id}.json"
    temp_meta_path = pending_dir / f".{transfer_id}.json.part"

    try:
        sha256, size = copy_file_with_sha256(source, temp_payload_path)
        transfer = PhoneTransfer(
            transfer_id=transfer_id,
            filename=filename,
            sha256=sha256,
            size=size,
            queued_at=_utc_now(),
            payload_path=final_payload_path,
        )

        temp_meta_path.write_text(
            json.dumps(transfer.to_payload(), indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
        temp_payload_path.replace(final_payload_path)
        temp_meta_path.replace(meta_path)
        return transfer
    except Exception:
        temp_meta_path.unlink(missing_ok=True)
        temp_payload_path.unlink(missing_ok=True)
        raise


def next_phone_transfer(queue_dir: Path, lease_seconds: int = DEFAULT_LEASE_SECONDS) -> PhoneTransfer | None:
    _recover_expired_leases(queue_dir)
    pending_dir = queue_dir / _PENDING_DIR
    if not pending_dir.exists():
        return None

    for meta_path in sorted(pending_dir.glob("*.json")):
        transfer = _load_transfer_from_meta(meta_path)
        if transfer is not None:
            return _lease_transfer(queue_dir, transfer, max(1, lease_seconds))
    return None


def get_phone_transfer(queue_dir: Path, transfer_id: str) -> PhoneTransfer | None:
    for dirname in (_INFLIGHT_DIR, _PENDING_DIR):
        transfer = _load_transfer_from_meta(queue_dir / dirname / f"{transfer_id}.json")
        if transfer is not None:
            return transfer
    return None


def acknowledge_phone_transfer(queue_dir: Path, transfer_id: str) -> bool:
    exists = False
    for dirname in (_INFLIGHT_DIR, _PENDING_DIR):
        meta_path = queue_dir / dirname / f"{transfer_id}.json"
        payload_path = queue_dir / dirname / f"{transfer_id}.payload"
        exists = exists or meta_path.exists() or payload_path.exists()
        meta_path.unlink(missing_ok=True)
        payload_path.unlink(missing_ok=True)
    return exists


def _load_transfer_from_meta(meta_path: Path) -> PhoneTransfer | None:
    if not meta_path.exists():
        return None

    try:
        payload = json.loads(meta_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None

    try:
        transfer_id = str(payload["transfer_id"])
        filename = sanitize_filename(str(payload["filename"]))
        sha256 = normalize_sha256(str(payload["sha256"]))
        size = int(payload["size"])
        queued_at = str(payload["queued_at"])
    except (KeyError, TypeError, ValueError):
        return None

    payload_path = meta_path.with_suffix(".payload")
    if not payload_path.exists():
        return None

    return PhoneTransfer(
        transfer_id=transfer_id,
        filename=filename,
        sha256=sha256,
        size=size,
        queued_at=queued_at,
        payload_path=payload_path,
        leased_at=str(payload.get("leased_at", "")),
        lease_expires_at=str(payload.get("lease_expires_at", "")),
    )


def _new_transfer_id() -> str:
    return datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + secrets.token_hex(4)


def _utc_now() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def _lease_transfer(queue_dir: Path, transfer: PhoneTransfer, lease_seconds: int) -> PhoneTransfer | None:
    pending_dir = queue_dir / _PENDING_DIR
    inflight_dir = queue_dir / _INFLIGHT_DIR
    inflight_dir.mkdir(parents=True, exist_ok=True)

    pending_meta_path = pending_dir / f"{transfer.transfer_id}.json"
    pending_payload_path = pending_dir / f"{transfer.transfer_id}.payload"
    inflight_meta_path = inflight_dir / f"{transfer.transfer_id}.json"
    inflight_payload_path = inflight_dir / f"{transfer.transfer_id}.payload"
    if not pending_meta_path.exists() or not pending_payload_path.exists():
        return None

    now = datetime.now(timezone.utc).replace(microsecond=0)
    leased = PhoneTransfer(
        transfer_id=transfer.transfer_id,
        filename=transfer.filename,
        sha256=transfer.sha256,
        size=transfer.size,
        queued_at=transfer.queued_at,
        payload_path=inflight_payload_path,
        leased_at=_format_utc(now),
        lease_expires_at=_format_utc(now + timedelta(seconds=lease_seconds)),
    )
    temp_meta_path = inflight_dir / f".{transfer.transfer_id}.json.part"

    pending_payload_path.replace(inflight_payload_path)
    temp_meta_path.write_text(
        json.dumps(leased.to_payload(), indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    temp_meta_path.replace(inflight_meta_path)
    pending_meta_path.unlink(missing_ok=True)
    return leased


def _recover_expired_leases(queue_dir: Path) -> None:
    inflight_dir = queue_dir / _INFLIGHT_DIR
    if not inflight_dir.exists():
        return

    now = datetime.now(timezone.utc)
    pending_dir = queue_dir / _PENDING_DIR
    pending_dir.mkdir(parents=True, exist_ok=True)
    for meta_path in sorted(inflight_dir.glob("*.json")):
        payload = _read_json(meta_path)
        if payload is None:
            continue
        if not _lease_expired(str(payload.get("lease_expires_at", "")), now):
            continue
        transfer = _load_transfer_from_meta(meta_path)
        if transfer is None:
            continue

        pending_payload_path = pending_dir / f"{transfer.transfer_id}.payload"
        pending_meta_path = pending_dir / f"{transfer.transfer_id}.json"
        payload.pop("leased_at", None)
        payload.pop("lease_expires_at", None)
        transfer.payload_path.replace(pending_payload_path)
        pending_meta_path.write_text(
            json.dumps(payload, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
        meta_path.unlink(missing_ok=True)


def _read_json(path: Path) -> dict[str, object] | None:
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None
    return payload if isinstance(payload, dict) else None


def _lease_expired(value: str, now: datetime) -> bool:
    if not value:
        return True
    try:
        expires_at = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return True
    if expires_at.tzinfo is None:
        expires_at = expires_at.replace(tzinfo=timezone.utc)
    return expires_at <= now


def _format_utc(value: datetime) -> str:
    return value.astimezone(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")
