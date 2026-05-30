# Source this file to expose WifiShare commands in the current shell:
# . /path/to/WifiShare/server/env.sh

if [ -n "${ZSH_VERSION:-}" ]; then
  _WIFISHARE_ENV_PATH="${(%):-%x}"
elif [ -n "${BASH_VERSION:-}" ]; then
  eval '_WIFISHARE_ENV_PATH="${BASH_SOURCE[0]}"'
else
  _WIFISHARE_ENV_PATH="$0"
fi
_WIFISHARE_ENV_DIR=$(CDPATH= cd -- "$(dirname "$_WIFISHARE_ENV_PATH")" && pwd)

export LAN_SECURE_SHARE_HOME="${LAN_SECURE_SHARE_HOME:-${_WIFISHARE_ENV_DIR}}"
export LAN_SECURE_SHARE_CONFIG="${LAN_SECURE_SHARE_CONFIG:-${LAN_SECURE_SHARE_HOME}/state/config.json}"
export LAN_SECURE_SHARE_DOWNLOAD_DIR="${LAN_SECURE_SHARE_DOWNLOAD_DIR:-${HOME}/Downloads/WifiShare}"
export LAN_SECURE_SHARE_PHONE_QUEUE_DIR="${LAN_SECURE_SHARE_PHONE_QUEUE_DIR:-${LAN_SECURE_SHARE_HOME}/state/phone-outbox}"

case ":${PATH}:" in
  *":${LAN_SECURE_SHARE_HOME}:"*) ;;
  *) export PATH="${LAN_SECURE_SHARE_HOME}:${PATH}" ;;
esac

unset _WIFISHARE_ENV_PATH
unset _WIFISHARE_ENV_DIR
