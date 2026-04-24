# WifiShare 服务端

这是 WifiShare 的 Linux/Python 服务端，负责：

- 接收 Android 通过 HTTPS 上传的文件。
- 维护 Linux 到手机的待接收队列。
- 生成 Android 配对信息。

## 安全模型

- 传输层使用 `HTTPS`，最低 TLS 1.3。
- Android 端固定校验服务端证书 SHA-256 指纹。
- 请求必须携带 Bearer token。
- 上传文件带 `X-Content-SHA256`，服务端保存前会重新计算并校验。

## 初始化

```bash
cd /home/iaw/Codex/work/2026-04-24/WifiShare/server
python -m lss_server init \
  --state-dir ./state \
  --server-name kali-host \
  --advertise-host 192.168.31.29
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

## 配对

刷新配对信息：

```bash
python -m lss_server pairing --config ./state/config.json --write
```

输出文件：

- `state/pairing.json`
- `state/pairing-uri.txt`

`pairing-uri.txt` 含有 token，不要发给不可信的人，不要上传到公网。

## 手机到电脑

默认保存目录来自配置或环境变量：

```bash
export LAN_SECURE_SHARE_DOWNLOAD_DIR=/home/iaw/Downloads/WifiShare
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

## 环境变量

- `LAN_SECURE_SHARE_CONFIG`：配置文件路径。
- `LAN_SECURE_SHARE_DOWNLOAD_DIR`：手机上传到电脑后的保存目录。
- `LAN_SECURE_SHARE_PHONE_QUEUE_DIR`：电脑发给手机的队列目录。
- `LAN_SECURE_SHARE_STATE_DIR`：初始化时默认 state 目录。

启用当前 shell 的快捷命令：

```bash
. /home/iaw/Codex/work/2026-04-24/WifiShare/server/env.sh
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
cp /home/iaw/Codex/work/2026-04-24/WifiShare/server/systemd/wifishare.service ~/.config/systemd/user/
cp /home/iaw/Codex/work/2026-04-24/WifiShare/server/systemd/server.env.example ~/.config/wifishare/server.env
$EDITOR ~/.config/wifishare/server.env
systemctl --user daemon-reload
systemctl --user enable --now wifishare.service
```

查看日志：

```bash
journalctl --user -u wifishare.service -f
```
