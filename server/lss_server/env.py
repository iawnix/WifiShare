from __future__ import annotations

import os
from pathlib import Path


ENV_CONFIG = "LAN_SECURE_SHARE_CONFIG"
ENV_DOWNLOAD_DIR = "LAN_SECURE_SHARE_DOWNLOAD_DIR"
ENV_PHONE_QUEUE_DIR = "LAN_SECURE_SHARE_PHONE_QUEUE_DIR"
ENV_STATE_DIR = "LAN_SECURE_SHARE_STATE_DIR"
LEGACY_ENV_UPLOAD_DIR = "LAN_SECURE_SHARE_UPLOAD_DIR"


def env_path(name: str) -> Path | None:
    value = os.environ.get(name, "").strip()
    if not value:
        return None
    return Path(value).expanduser()


def download_dir_from_env() -> Path | None:
    return env_path(ENV_DOWNLOAD_DIR) or env_path(LEGACY_ENV_UPLOAD_DIR)


def state_dir_from_env(default: Path) -> Path:
    return env_path(ENV_STATE_DIR) or default


def config_path_from_env(default: Path) -> Path:
    return env_path(ENV_CONFIG) or default
