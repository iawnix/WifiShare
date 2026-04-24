from __future__ import annotations

from urllib.parse import urlencode

from .config import ServerConfig


def build_pairing_payload(config: ServerConfig, certificate_sha256: str) -> dict[str, str]:
    return {
        "server_name": config.server_name,
        "base_url": config.base_url,
        "auth_token": config.auth_token,
        "certificate_sha256": certificate_sha256,
    }


def build_pairing_uri(payload: dict[str, str]) -> str:
    return "lss://pair?" + urlencode(payload)
