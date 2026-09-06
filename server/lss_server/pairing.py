from __future__ import annotations

from urllib.parse import urlencode

from .config import ServerConfig


def build_pairing_payload(
    config: ServerConfig,
    certificate_sha256: str,
    *,
    auth_token: str | None = None,
    device_id: str = "",
    expires_at: str = "",
) -> dict[str, str]:
    payload = {
        "server_name": config.server_name,
        "base_url": config.base_url,
        "auth_token": auth_token if auth_token is not None else config.auth_token,
        "certificate_sha256": certificate_sha256,
    }
    if device_id:
        payload["device_id"] = device_id
    if expires_at:
        payload["expires_at"] = expires_at
    return payload


def build_pairing_uri(payload: dict[str, str]) -> str:
    return "lss://pair?" + urlencode(payload)
