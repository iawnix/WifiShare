# WifiShare 服务端

[返回项目首页](../README.md) · [故障排查](../docs/troubleshooting.md) · [开发指南](../docs/development.md)

Linux / Python HTTPS 服务端，负责接收手机上传、维护电脑发往手机的文件队列，以及管理配对凭据。
首次安装步骤见[快速开始](../README.md#quickstart)。本文是安装参数与服务端维护的完整参考。

## 环境与命令目录

- 按 Python 3.13 或更新版本准备环境，安装 OpenSSL；运行时使用 Python 标准库，无第三方 pip 依赖。
- 安装器依赖 Unix shell 与 Linux 工具；systemd 用户服务可选，不提供 Windows / macOS 原生安装支持。
- 除特别标注的仓库根目录命令外，下文 Python CLI 和 `./serve`、`./phone` 均在仓库的 `server/` 目录执行：

```bash
cd /path/to/WifiShare/server
```

请替换路径。各代码块是独立示例，不需要全部顺序执行。
默认配置为 `./state/config.json`；使用自定义配置时，每个命令的 `--config` 都应指向同一文件。

<a id="install"></a>
## 安装参数

以下命令**仅用于首次安装**，在仓库根目录执行：

```bash
./install_wifishare --ip 192.168.1.50 --shell bash
```

首次安装时同时指定目录、容量和后台服务的示例：

```bash
./install_wifishare --ip 192.168.1.50 \
  --shell fish \
  --relay_dir ~/Downloads/WifiShare \
  --max-upload-mb 4096 \
  --max-outbox-file-mb 4096 \
  --max-storage-mb 10240 \
  --enable_systemd
```

| 参数 | 行为 |
| --- | --- |
| `--ip` | 手机可访问的局域网地址；指定后会初始化配置、重建证书、重置设备 Token |
| `--shell bash\|zsh\|fish` | 写入对应 shell 的加载入口；不指定时根据当前 shell 判断 |
| `--relay_dir` | 手机上传保存目录，默认 `~/Downloads/WifiShare` |
| `--max-upload-mb` | 手机到电脑单文件上限 |
| `--max-outbox-file-mb` | 电脑到手机单文件上限 |
| `--max-storage-mb` | 上传目录和发送队列各自的容量上限 |
| `--enable_systemd` | 安装、启用并立即重启用户服务，不适合在传输中执行 |
| `repair` | 重写环境与加载入口；不是保证保留全部配置的无副作用修复 |
| `--help` | 只打印帮助；`--enable_systemed` 是后台服务参数的兼容拼写 |

安装器固定使用本仓库的 `server/state/`。它会写入 `~/.config/wifishare/`、
所选 shell 的启动文件，以及指定的接收目录；请用运行服务的普通用户执行，不要使用 `sudo`。

配对 URI 与 Token 只在终端打印，不写入配对文件。安装器也会删除 state 中旧的
`pairing.json` 和 `pairing-uri.txt`；终端回滚记录仍可能留有凭据，请保护好它。

<a id="startup"></a>
## 启动与后台服务

### 手动运行

Bash / Zsh 中加载安装器生成的环境后启动：

```bash
. ~/.config/wifishare/env.sh
serve
```

Fish 使用自己的环境文件：

```fish
source ~/.config/wifishare/env.fish
serve
```

`serve` 占用前台终端，退出终端会影响服务；手动停止使用 Ctrl+C。
也可在 `server/` 直接执行 `./serve --config ./state/config.json`，无需加载短命令。

### systemd 用户服务

首次安装使用 `--enable_systemd` 后，查看状态与日志：

```bash
systemctl --user status wifishare.service
journalctl --user -u wifishare.service -n 100 --no-pager
```

已有安装改用后台服务时，先确认无传输任务，停止手动运行的 `serve`，
然后在仓库根目录执行以下命令。**不要追加 `--ip`**；若原来使用自定义接收目录，
必须将示例 `--relay_dir` 改为原目录，否则生成的环境会改回默认目录：

```bash
./install_wifishare repair --shell bash \
  --relay_dir ~/Downloads/WifiShare --enable_systemd
```

此命令会签发一个新的设备 Token，但不自动撤销已有 Token，并立即重启服务。
它不是仅复制一个 unit 文件的操作。使用自定义 `--config` 或外部 state 的部署不应套用此命令。

日常管理：

```bash
systemctl --user stop wifishare.service
systemctl --user start wifishare.service
systemctl --user restart wifishare.service
journalctl --user -u wifishare.service -f
```

上面分别为停止、启动、重启和持续查看日志，按需执行，不是顺序安装步骤。
用户服务能否在退出登录后继续运行取决于系统的用户管理器与 linger 策略，安装器不会配置 linger。
已运行后台服务时不要再开一个 `serve`。

需要手动管理时参考 [unit 模板](systemd/wifishare.service)和[环境模板](systemd/server.env.example)。
unit 位于 `~/.config/systemd/user/wifishare.service`，环境文件位于 `~/.config/wifishare/server.env`；
修改 unit 后需 `systemctl --user daemon-reload`，修改服务环境后需重启服务。

<a id="tokens"></a>
## 配对与 Token

### 新增设备或重新签发

在 `server/` 执行：

```bash
python3 -m lss_server pairing --config ./state/config.json \
  --device-name "Samsung S23" \
  --token-expires-days 365 \
  --scopes upload,outbox.read,outbox.ack
```

每次执行都会创建新的设备 Token，并输出配对 JSON 和 `Pairing URI`。
**它不是查看旧 Token 的命令**：服务端只保存摘要，不能取回新式 Token 的旧明文。
仅重启服务不会更换 Token，也不要求重新配对。

在手机打开配对 URI，或在设置中新增 / 编辑服务器：

| 手机字段 | 配对输出 |
| --- | --- |
| Base URL | `base_url`，形如 `https://192.168.1.50:8443` |
| 认证 Token | `auth_token` 的值，不含引号，不加 `Bearer ` |
| Cert SHA-256 | `certificate_sha256` |

默认有效期为 365 天。日常双向传输使用示例中的全部三个权限；
`upload` 用于上传，`outbox.read` 用于领取与下载，`outbox.ack` 用于确认完成。

### 查看和撤销设备

```bash
python3 -m lss_server devices list --config ./state/config.json
```

确认列表中的目标 ID 后，将下面的 `DEVICE_ID` 替换为实际 ID：

```bash
python3 -m lss_server devices revoke DEVICE_ID --config ./state/config.json
```

签发和撤销对后续请求即时生效，不保证中断已经开始的请求。
重新配对不会自动撤销旧 Token；正常轮换时先确认新 Token 可用，再撤销旧设备。
若怀疑凭据已泄露，应优先撤销泄露凭据。

### 从旧共享 Token 迁移

```bash
python3 -m lss_server devices migrate-legacy --config ./state/config.json
```

首次迁移会把磁盘配置中的旧共享 Token 转为设备记录；`pairing` 也会触发此迁移。
迁移后在无传输任务时重启服务，清除旧进程保留的共享 Token。
确认新设备凭据可用后，可在设备列表中找到旧记录并撤销。此后普通签发与撤销不需要重启。

### 配对文件

默认 `pairing` 不写文件。只有明确需要临时文件时才追加 `--write`；
这仍然会签发新 Token，并在配置目录写入 `pairing.json` 和 `pairing-uri.txt`。
两者含明文凭据，应只通过可信通道传递，完成后删除，不得提交到 Git 或公开 Issue。

<a id="files"></a>
## 文件与保存目录

在 `server/` 加入一个或多个文件：

```bash
./phone "/path/to/file.pdf" "/path/to/photo.jpg"
```

`phone` 把文件复制到发送队列，不会删除源文件，也不会主动推送到手机。
手机点击 App 或小组件的下载图标后领取文件，保存到 `Downloads/WifiShare/`；
确认接收成功后，服务端删除对应队列副本。手机接收目录目前不可自定义。

安装器默认把手机上传文件保存到电脑的 `~/Downloads/WifiShare`。
直接手动初始化且未指定目录或环境覆盖时，才默认使用 `state/uploads/`。
最终保存位置取决于配置及[环境覆盖](#environment)，并非总是 state 目录。

队列由同一服务器上具备接收权限的设备共用，**不按设备 Token 分成独立收件箱**。
多台手机配对同一服务端时，文件可能被先领取的手机接收。

<a id="limits"></a>
## 文件上限与容量

以下为默认值；安装时传入参数或修改过配置的部署以实际值为准。

| 配置参数 | 默认值 | 含义 |
| --- | --- | --- |
| `--max-upload-mb` | 1024 MiB | 手机上传的单文件上限 |
| `--max-outbox-file-mb` | 4096 MiB | 电脑加入发送队列的单文件上限 |
| `--max-storage-mb` | 10240 MiB | 上传目录、发送队列各自的容量上限 |
| `--min-free-mb` | 512 MiB | 最低剩余磁盘空间 |

在 `server/` 调整现有配置，不需要轮换证书或 Token：

```bash
python3 -m lss_server configure --config ./state/config.json \
  --max-upload-mb 4096 \
  --max-outbox-file-mb 8192 \
  --max-storage-mb 20480 \
  --min-free-mb 1024
```

单位是 MiB，不是字节；容量上限应大于目标单文件，并为已有文件留出空间。
修改后等待任务结束，再重启 `wifishare.service`；手动运行则停止并重新执行 `serve`。
无需重新安装 App 或重新配对。

高级参数还包括 `--max-concurrent-requests`、`--request-timeout-seconds`、
`--tls-handshake-timeout-seconds` 和 `--requests-per-minute`；可用
`python3 -m lss_server configure --help` 查看。
超出上传单文件上限返回 HTTP 413，空间不足返回 507，超过请求速率返回 429；
电脑排队超限则由 `phone` 在本地报错。

<a id="environment"></a>
## 环境与状态文件

| 变量 | 用途 |
| --- | --- |
| `LAN_SECURE_SHARE_CONFIG` | Python CLI 默认配置；显式 `--config` 优先 |
| `LAN_SECURE_SHARE_DOWNLOAD_DIR` | 覆盖手机上传保存目录 |
| `LAN_SECURE_SHARE_PHONE_QUEUE_DIR` | 覆盖发送队列目录 |
| `LAN_SECURE_SHARE_STATE_DIR` | Python `init` 默认 state 目录；安装器使用固定仓库路径 |
| `LAN_SECURE_SHARE_HOME` | shell 加载文件中的服务端目录 |
| `WIFISHARE_HOME` | systemd 模板使用的仓库根目录 |

注意：`./phone` 包装脚本在没有显式 `--config` 时固定使用本仓库的
`state/config.json`，不沿用 `LAN_SECURE_SHARE_CONFIG`；自定义配置必须显式传入。
交互 shell 的环境不会自动传给已经运行的 systemd 服务，请核对 `server.env`。

`state/` 中的配置、证书私钥、设备 Token 摘要和队列属于本机运行数据，已被 Git 忽略。
需备份时使用受保护的本地位置，保留权限；不要把 state、配对文件或私钥发到公开仓库。

<a id="maintenance"></a>
## 维护与重新初始化

- 仅更新 Android APK：不需要重启服务端，除非对应更新说明另有要求。
- 更新服务端代码：保留原 `state/` 与接收目录；阅读更新说明，在任务结束后按需重启。
- 重新 clone 到新目录：源码不含旧 state，不能自动继承原来的配对。
- 仓库搬家：已有配置和环境可能使用绝对路径，不能只移动目录就假定服务已迁移。

**`repair --ip ...` 不是无损修复。** 当前安装器只要收到 `--ip` 就执行初始化，
覆盖证书与配置并重置 Token 库。只调整容量用 `configure`，只增加设备用 `pairing`。
不带 `--ip` 的 repair 也会重写环境；未传容量参数时还会签发新 Token，
并删除配对文件。自定义接收目录需要显式保留。

只有确实要建立新服务端身份时，才使用安装器的 `--ip` 或下面的手动初始化。
对已有部署先停止传输、停止服务并安全备份配置、凭据与队列；之后所有手机需要重新配对。

手动初始化的示例，仅限新的 state 目录，在 `server/` 执行：

```bash
python3 -m lss_server init --state-dir ./state \
  --server-name WifiShare --advertise-host 192.168.1.50 \
  --no-write-pairing
```

`--no-write-pairing` 表示只在终端打印配对信息；省略时会写出含凭据的配对文件。
这不是启动命令，启动请使用 `./serve`。

<a id="security"></a>
## 安全模型

- HTTPS 最低 TLS 1.3，Android 固定校验服务端证书 SHA-256 指纹。
- 按设备使用 Bearer Token，服务端只保存新式 Token 的摘要，支持权限、有效期及撤销。
- 文件内容使用 SHA-256 校验；服务端设有握手 / 请求超时、并发、速率和存储限制。
- 敏感配置、私钥、Token 库和传输临时文件使用私有权限；不完整上传会清理临时文件。
- Android 使用 Keystore + AES-GCM 保存凭据，App 数据不进入系统备份。

只在可信局域网使用，不要直接向公网开放监听端口。配对 URI 仍含长期 Token，
`lss://` 可能被其他 App 注册；通过可信渠道传递并核对地址与指纹。
这些措施不替代公网身份系统、防火墙、一次性配对协议或独立渗透测试。
