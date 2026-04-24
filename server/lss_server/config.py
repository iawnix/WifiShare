from __future__ import annotations

from dataclasses import asdict, dataclass
import json
from pathlib import Path

from .env import download_dir_from_env, env_path, ENV_PHONE_QUEUE_DIR


@dataclass(slots=True)
class ServerConfig:
    server_name: str
    listen_host: str
    listen_port: int
    advertise_host: str
    auth_token: str
    cert_file: str
    key_file: str
    upload_dir: str
    phone_queue_dir: str = ""
    max_upload_mb: int = 1024

    @property
    def base_url(self) -> str:
        return f"https://{self.advertise_host}:{self.listen_port}"

    @property
    def upload_limit_bytes(self) -> int:
        return self.max_upload_mb * 1024 * 1024

    @property
    def phone_queue_path(self) -> Path:
        if self.phone_queue_dir.strip():
            return Path(self.phone_queue_dir)
        return Path(self.upload_dir).parent / "phone-outbox"

    def validate(self) -> None:
        if not self.server_name.strip():
            raise ValueError("server_name must not be empty")
        if not self.listen_host.strip():
            raise ValueError("listen_host must not be empty")
        if not self.advertise_host.strip():
            raise ValueError("advertise_host must not be empty")
        if not (1 <= self.listen_port <= 65535):
            raise ValueError("listen_port must be between 1 and 65535")
        if len(self.auth_token.strip()) < 24:
            raise ValueError("auth_token is too short")
        if self.max_upload_mb <= 0:
            raise ValueError("max_upload_mb must be positive")


def load_config(path: Path) -> ServerConfig:
    config_path = path.expanduser().resolve()
    payload = json.loads(config_path.read_text(encoding="utf-8"))
    config = ServerConfig(**payload)
    _resolve_relative_paths(config_path, config)
    _apply_env_overrides(config)
    config.validate()
    return config


def _resolve_relative_paths(config_path: Path, config: ServerConfig) -> None:
    config.cert_file = _resolve_config_path(config_path, config.cert_file)
    config.key_file = _resolve_config_path(config_path, config.key_file)
    config.upload_dir = _resolve_config_path(config_path, config.upload_dir)
    if config.phone_queue_dir.strip():
        config.phone_queue_dir = _resolve_config_path(config_path, config.phone_queue_dir)


def _resolve_config_path(config_path: Path, value: str) -> str:
    path = Path(value).expanduser()
    if path.is_absolute():
        return str(path)

    config_dir = config_path.parent
    if path.parts and path.parts[0] == config_dir.name:
        return str(config_dir.parent / path)
    return str(config_dir / path)


def _apply_env_overrides(config: ServerConfig) -> None:
    download_dir = download_dir_from_env()
    if download_dir is not None:
        config.upload_dir = str(download_dir.resolve())

    phone_queue_dir = env_path(ENV_PHONE_QUEUE_DIR)
    if phone_queue_dir is not None:
        config.phone_queue_dir = str(phone_queue_dir.resolve())


def save_config(path: Path, config: ServerConfig) -> None:
    config.validate()
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(asdict(config), indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
