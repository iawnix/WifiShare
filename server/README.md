# WifiShare 服务端

这是 WifiShare 的 Linux/Python 服务端，负责：

- 接收 Android 通过 HTTPS 上传的文件。
- 维护 Linux 到手机的待接收队列。
- 生成 Android 配对信息。

## 安全模型

- 传输层使用 `HTTPS`，最低 TLS 1.3。
- Android 端固定校验服务端证书 SHA-256 指纹。
- 请求必须携带独立设备 Bearer Token；服务端只保存 SHA-256 摘要。
- Token 分别授权 `upload`、`outbox.read`、`outbox.ack`，支持有效期和撤销。
- 上传文件带 `X-Content-SHA256`，服务端保存前会重新计算并校验。
- 服务端限制 TLS 握手时间、请求时间、并发数、每 IP 请求速率、单文件大小、目录容量和最低剩余磁盘空间。
- 配置、Token、私钥、上传临时文件和队列文件使用私有权限；不完整上传不会保留 `.part` 文件。

WifiShare 面向可信局域网，不应把监听端口直接映射到公网。当前配对 URI 含长期设备
Token，自定义 `lss://` Scheme 也可能被其他 App 注册；应只通过可信通道短时传递并在
完成配对后删除。此安全模型没有替代公网身份认证、WAF 或独立渗透测试。

## 推荐安装

从仓库根目录执行：

```bash
./install_wifishare --ip 192.168.1.50 --shell bash
```

常用完整形式：

```bash
./install_wifishare \
  --ip 192.168.1.50 \
  --enable_systemd \
  --shell fish \
  --relay_dir ~/Downloads/WifiShare \
  --max-upload-mb 4096 \
  --max-outbox-file-mb 4096 \
  --max-storage-mb 10240
```

- `--ip`：手机能访问到的 Linux 局域网 IP。
- `--enable_systemd`：安装并启动用户级 `wifishare.service`；默认不启用，只打印手动启动说明。
- `--enable_systemed`：兼容拼写别名，等同于 `--enable_systemd`。
- `--shell`：写入 bash、zsh 或 fish 的环境加载入口。
- `--relay_dir`：手机上传到电脑的保存目录，默认 `~/Downloads/WifiShare`。
- `--max-upload-mb`：手机到电脑的单文件上限，默认 `1024 MiB`。
- `--max-outbox-file-mb`：电脑到手机的单文件上限，默认 `4096 MiB`。
- `--max-storage-mb`：上传目录和发送队列各自的容量上限，默认 `10240 MiB`。
- `repair`：重建 env、目录、配置和可选 systemd 单元。
- `-h`：打印安装说明。

安装脚本会签发新的设备 Token，并把配对信息只在终端打印；不会把手机配对 URI/token 写入本地
`pairing.json` 或 `pairing-uri.txt`。

## 手动初始化

```bash
LAN_IP=192.168.1.50
cd WifiShare/server
python -m lss_server init \
  --state-dir ./state \
  --server-name WifiShare \
  --advertise-host "$LAN_IP"
```

`advertise-host` 必须是手机能访问到的 Linux 局域网 IP。

## 启动

```bash
python -m lss_server serve --config ./state/config.json
```

或者：

```bash
. ./env.sh
serve
```

终端保持运行，手机上传或拉取文件时会在这里打印请求日志。

## 手动配对

刷新配对信息：

```bash
python -m lss_server pairing --config ./state/config.json --write
```

输出文件：

- `state/pairing.json`
- `state/pairing-uri.txt`

`pairing-uri.txt` 含有 token，不要发给不可信的人，不要上传到公网。

每次执行 `pairing` 都会创建新的设备 Token。可指定名称、有效期和权限：

```bash
python3 -m lss_server pairing --config ./state/config.json \
  --device-name "Pixel" \
  --token-expires-days 365 \
  --scopes upload,outbox.read,outbox.ack
```

查看或撤销设备：

```bash
python3 -m lss_server devices list --config ./state/config.json
python3 -m lss_server devices revoke <device_id> --config ./state/config.json
python3 -m lss_server devices migrate-legacy --config ./state/config.json
```

`pairing` 会输出新的 `auth_token` 和完整配对 URI，不会自动撤销旧设备 Token。
手机可打开 URI 确认配对，或在 App 设置中编辑服务端并填写输出的
`base_url`、`auth_token` 和 `certificate_sha256`，再保存。

首次从旧版共享 Token 迁移时，`pairing` 或 `devices migrate-legacy` 更新磁盘配置后，
需要在无传输任务时重启 `wifishare.service`（手动启动则重新执行 `serve`），
清除进程中保留的旧共享 Token。此后设备 Token 的签发、撤销对后续请求即时生效。

## 手机到电脑

默认保存目录来自配置或环境变量：

```bash
export LAN_SECURE_SHARE_DOWNLOAD_DIR="$HOME/Downloads/WifiShare"
```

如果没有设置环境变量，默认保存到 `state/uploads/`。

## 电脑到手机

加入发送队列：

```bash
./phone /path/to/file.pdf
```

手机端点击“接收队列文件”后会拉取并保存到：

```text
Downloads/WifiShare/
```

## 调整传输限制

无需重建证书或轮换 Token：

```bash
python3 -m lss_server configure --config ./state/config.json \
  --max-upload-mb 4096 \
  --max-outbox-file-mb 8192 \
  --max-storage-mb 20480 \
  --min-free-mb 1024
```

配置值单位为 MiB。修改后必须重启服务：

```bash
systemctl --user restart wifishare.service
```

手动运行时则停止并重新执行 `serve`。超出单文件上限返回 HTTP 413，存储容量或剩余
空间不足返回 HTTP 507，超过每 IP 速率限制返回 HTTP 429。

## 环境变量

- `LAN_SECURE_SHARE_CONFIG`：配置文件路径。
- `LAN_SECURE_SHARE_DOWNLOAD_DIR`：手机上传到电脑后的保存目录。
- `LAN_SECURE_SHARE_PHONE_QUEUE_DIR`：电脑发给手机的队列目录。
- `LAN_SECURE_SHARE_STATE_DIR`：初始化时默认 state 目录。

启用当前 shell 的快捷命令：

```bash
. ./env.sh
```

## systemd 用户服务

模板文件：

```text
systemd/wifishare.service
```

示例环境文件：

```text
systemd/server.env.example
```

安装示例：

```bash
mkdir -p ~/.config/systemd/user ~/.config/wifishare
cp systemd/wifishare.service ~/.config/systemd/user/
cp systemd/server.env.example ~/.config/wifishare/server.env
$EDITOR ~/.config/wifishare/server.env
systemctl --user daemon-reload
systemctl --user enable wifishare.service
systemctl --user restart wifishare.service
```

查看日志：

```bash
journalctl --user -u wifishare.service -f
```
