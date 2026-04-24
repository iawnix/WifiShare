from __future__ import annotations

import hashlib
import ipaddress
import os
from pathlib import Path
import secrets
import ssl
import subprocess


def generate_auth_token() -> str:
    return secrets.token_urlsafe(32)


def _san_entries(advertise_host: str) -> str:
    entries = {"DNS:localhost", "IP:127.0.0.1"}
    try:
        ipaddress.ip_address(advertise_host)
    except ValueError:
        entries.add(f"DNS:{advertise_host}")
    else:
        entries.add(f"IP:{advertise_host}")
    return ",".join(sorted(entries))


def generate_self_signed_certificate(
    cert_path: Path,
    key_path: Path,
    common_name: str,
    advertise_host: str,
) -> None:
    cert_path.parent.mkdir(parents=True, exist_ok=True)
    key_path.parent.mkdir(parents=True, exist_ok=True)

    command = [
        "openssl",
        "req",
        "-x509",
        "-newkey",
        "rsa:3072",
        "-sha256",
        "-days",
        "825",
        "-nodes",
        "-subj",
        f"/CN={common_name}",
        "-addext",
        f"subjectAltName={_san_entries(advertise_host)}",
        "-keyout",
        str(key_path),
        "-out",
        str(cert_path),
    ]
    try:
        subprocess.run(command, check=True, capture_output=True, text=True)
    except FileNotFoundError as exc:
        raise RuntimeError("openssl is required to generate a certificate") from exc
    except subprocess.CalledProcessError as exc:
        raise RuntimeError(exc.stderr.strip() or "openssl failed to generate a certificate") from exc

    os.chmod(key_path, 0o600)


def certificate_sha256(cert_path: Path) -> str:
    pem_text = cert_path.read_text(encoding="utf-8")
    der_bytes = ssl.PEM_cert_to_DER_cert(pem_text)
    return hashlib.sha256(der_bytes).hexdigest()
