# Source this file to expose WifiShare commands in the current shell:
# . /home/iaw/Codex/work/2026-04-24/WifiShare/server/env.sh

export LAN_SECURE_SHARE_HOME="/home/iaw/Codex/work/2026-04-24/WifiShare/server"
export LAN_SECURE_SHARE_CONFIG="${LAN_SECURE_SHARE_CONFIG:-${LAN_SECURE_SHARE_HOME}/state/config.json}"
export LAN_SECURE_SHARE_DOWNLOAD_DIR="${LAN_SECURE_SHARE_DOWNLOAD_DIR:-${LAN_SECURE_SHARE_HOME}/state/uploads}"
export LAN_SECURE_SHARE_PHONE_QUEUE_DIR="${LAN_SECURE_SHARE_PHONE_QUEUE_DIR:-${LAN_SECURE_SHARE_HOME}/state/phone-outbox}"

case ":${PATH}:" in
  *":${LAN_SECURE_SHARE_HOME}:"*) ;;
  *) export PATH="${LAN_SECURE_SHARE_HOME}:${PATH}" ;;
esac
