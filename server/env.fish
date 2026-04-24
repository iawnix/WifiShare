# Source this file to expose WifiShare commands in the current fish shell:
# source /path/to/WifiShare/server/env.fish

set -l _wifishare_env_path (status --current-filename)
set -l _wifishare_env_dir (cd (dirname "$_wifishare_env_path"); and pwd)

if not set -q LAN_SECURE_SHARE_HOME
    set -gx LAN_SECURE_SHARE_HOME "$_wifishare_env_dir"
end

if not set -q LAN_SECURE_SHARE_CONFIG
    set -gx LAN_SECURE_SHARE_CONFIG "$LAN_SECURE_SHARE_HOME/state/config.json"
end

if not set -q LAN_SECURE_SHARE_DOWNLOAD_DIR
    set -gx LAN_SECURE_SHARE_DOWNLOAD_DIR "$LAN_SECURE_SHARE_HOME/state/uploads"
end

if not set -q LAN_SECURE_SHARE_PHONE_QUEUE_DIR
    set -gx LAN_SECURE_SHARE_PHONE_QUEUE_DIR "$LAN_SECURE_SHARE_HOME/state/phone-outbox"
end

if not contains -- "$LAN_SECURE_SHARE_HOME" $PATH
    set -gx PATH "$LAN_SECURE_SHARE_HOME" $PATH
end


