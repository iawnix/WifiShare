from __future__ import annotations

import hashlib
from pathlib import Path
import re


_INVALID_FILENAME_CHARS = re.compile(r"[^A-Za-z0-9._ ()+-]")
_HEX64 = re.compile(r"^[0-9a-f]{64}$")


def sanitize_filename(name: str) -> str:
    candidate = Path(name).name.strip().strip(".")
    candidate = _INVALID_FILENAME_CHARS.sub("_", candidate)
    return candidate or "upload.bin"


def normalize_sha256(value: str) -> str:
    normalized = re.sub(r"[^0-9A-Fa-f]", "", value or "").lower()
    if not _HEX64.fullmatch(normalized):
        raise ValueError("invalid sha256 digest")
    return normalized


def unique_destination(directory: Path, filename: str) -> Path:
    target = directory / filename
    if not target.exists():
        return target

    stem = target.stem or "upload"
    suffix = target.suffix
    counter = 1
    while True:
        candidate = directory / f"{stem}-{counter}{suffix}"
        if not candidate.exists():
            return candidate
        counter += 1


def copy_file_with_sha256(source: Path, destination: Path) -> tuple[str, int]:
    digest = hashlib.sha256()
    total_size = 0

    with source.open("rb") as input_handle, destination.open("wb") as output_handle:
        while True:
            chunk = input_handle.read(1024 * 1024)
            if not chunk:
                break
            output_handle.write(chunk)
            digest.update(chunk)
            total_size += len(chunk)

    return digest.hexdigest(), total_size
