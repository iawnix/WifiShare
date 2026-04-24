from __future__ import annotations

from dataclasses import asdict, dataclass
from datetime import datetime, timezone
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

    def to_payload(self) -> dict[str, object]:
        payload = asdict(self)
        payload.pop("payload_path", None)
        return payload


def queue_phone_file(queue_dir: Path, source_path: Path) -> PhoneTransfer:
    source = source_path.expanduser()
    if not source.exists():
        raise FileNotFoundError(source)
    if not source.is_file():
        raise ValueError(f"not a regular file: {source}")

    pending_dir = queue_dir / "pending"
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


def next_phone_transfer(queue_dir: Path) -> PhoneTransfer | None:
    pending_dir = queue_dir / "pending"
    if not pending_dir.exists():
        return None

    for meta_path in sorted(pending_dir.glob("*.json")):
        transfer = _load_transfer_from_meta(meta_path)
        if transfer is not None:
            return transfer
    return None


def get_phone_transfer(queue_dir: Path, transfer_id: str) -> PhoneTransfer | None:
    meta_path = queue_dir / "pending" / f"{transfer_id}.json"
    return _load_transfer_from_meta(meta_path)


def acknowledge_phone_transfer(queue_dir: Path, transfer_id: str) -> bool:
    pending_dir = queue_dir / "pending"
    meta_path = pending_dir / f"{transfer_id}.json"
    payload_path = pending_dir / f"{transfer_id}.payload"
    exists = meta_path.exists() or payload_path.exists()
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
    )


def _new_transfer_id() -> str:
    return datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + secrets.token_hex(4)


def _utc_now() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")
