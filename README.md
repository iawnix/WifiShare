# WifiShare

## 目标

WifiShare 是一个局域网加密文件传输原型，用于 Android 手机和 Linux 电脑之间互传文件。

- 手机到电脑：在 Android 系统分享菜单中选择 `WifiShare`，文件通过 HTTPS 上传到 Linux。
- 电脑到手机：Linux 端用 `phone <文件>` 加入队列，手机端点击“接收队列文件”拉取。
- 手机可保存多个 Linux 服务端配置，并在首页或设置页切换当前用于传输的服务端。
- Linux 端使用 Python；Android 端使用 Kotlin 原生分享入口。

## 目录

- `server/`：Python HTTPS 服务端，负责接收手机上传、维护发给手机的队列。
- `android/`：Android App 源码。
- `android/WifiShare-v0.12.0-release.apk`：推荐安装的正式签名、非调试 release APK。
- `android/WifiShare-debug.apk`：用于开发诊断的 debug APK。

`server/state/` 保存本机证书、token、配对链接和传输队列，只用于本机运行，已被 Git 忽略，不应上传到公开仓库。

## 另一台电脑安装使用

新电脑不要直接复用旧手机配置。每台电脑应重新生成自己的 `state/config.json`、证书、token 和配对链接。

1. 获取代码：

   ```bash
   git clone https://github.com/iawnix/WifiShare.git
   cd WifiShare/server
   ```

   也可以把这个仓库放在任意本地目录；后续命令以仓库根目录为基准。

2. 确认依赖：

   ```bash
   python3 --version
   openssl version
   ```

   服务端需要 `Python >= 3.13` 和 `openssl`。手机和电脑必须在同一个局域网，电脑防火墙需要允许 `8443/tcp`。

3. 找到新电脑的局域网 IP：

   ```bash
   ip -4 addr
   ```

   选择手机能访问到的地址，不要用 `127.0.0.1`。

4. 一键安装服务端：

   ```bash
   ./install_wifishare --ip 192.168.1.50 --shell zsh
   ```

   可选参数：

   ```bash
   ./install_wifishare --ip 192.168.1.50 \
     --enable_systemd \
     --shell fish \
     --relay_dir ~/Downloads/WifiShare \
     --max-upload-mb 4096 \
     --max-outbox-file-mb 4096 \
     --max-storage-mb 10240
   ```

   `--ip` 必须是手机能访问到的 Linux 局域网 IP。`--relay_dir` 默认是
   `~/Downloads/WifiShare`，用于保存手机发到电脑的文件。安装脚本会初始化
   `server/state/config.json`、证书和独立设备 Token，并只在终端打印本次配对 URI，不把
   手机配对 URI/token 另存成本地 `pairing-uri.txt`。

5. 启动服务：

   如果没有启用 systemd：

   ```bash
   . ~/.config/wifishare/env.sh
   serve
   ```

   如果安装时用了 `--enable_systemd`：

   ```bash
   systemctl --user status wifishare.service
   journalctl --user -u wifishare.service -f
   ```

6. 重新配对手机：

   安装脚本终端输出里有 `Pairing URI:`。把该链接发到手机打开，或临时做成二维码。
   手机端会保存新电脑的 `Base URL`、`Auth token` 和证书指纹，并自动切换到这个新服务端；旧服务端配置会保留在手机端列表里。
   每次生成配对信息都会签发新的设备 Token；完成配对后不要继续转发或长期保存该 URI。

7. 使用：

   ```bash
   phone /path/to/file.pdf
   ```

   然后在手机 App 首页点击“接收”。手机发电脑时，在 Android 分享菜单选择 `WifiShare`，再点“发送”。

修复或重建本机配置：

```bash
install_wifishare repair --ip 192.168.1.50 --shell bash
```

## 本机快速使用

1. 安装 Linux 服务端：

   ```bash
   cd WifiShare
   ./install_wifishare --ip 192.168.1.50 --shell bash
   ```

   不要用 `127.0.0.1`，要填手机能访问到的 Linux 局域网 IP。

2. 启动 Linux 服务端：

   ```bash
   . ~/.config/wifishare/env.sh
   serve
   ```

3. 安装 Android APK：

   ```text
   android/WifiShare-v0.12.0-release.apk
   ```

   `0.12.0` 首次改用独立的正式发布证书，不能覆盖使用旧 Debug/迁移证书安装的版本。
   若系统提示签名不一致，需要先卸载旧版再安装；卸载会清除 App 内服务器配置，安装后必须重新配对。

4. 配对手机：

   使用安装脚本打印的 `Pairing URI:`。也可以在 App 设置页手动填写 `Base URL`、`Auth token`、`Cert SHA-256`。手机端支持保存多个服务端，首页和设置页都可以切换当前连接的服务端。

5. 手机发送到电脑：

   在手机文件管理器或相册里点击系统分享，选择 `WifiShare`，再点“发送”。

6. 电脑发送到手机：

   ```bash
   phone /path/to/file.pdf
   ```

   然后在手机 App 首页点击“接收”。

## 环境变量

- `LAN_SECURE_SHARE_CONFIG`：服务端配置文件路径。
- `LAN_SECURE_SHARE_DOWNLOAD_DIR`：手机上传到电脑后的保存目录。
- `LAN_SECURE_SHARE_PHONE_QUEUE_DIR`：电脑发给手机的队列目录。
- `LAN_SECURE_SHARE_STATE_DIR`：初始化时默认 state 目录。

当前 shell 里启用短命令：

```bash
. /path/to/WifiShare/server/env.sh
serve
phone /path/to/file.pdf
```

fish shell 使用：

```fish
source /path/to/WifiShare/server/env.fish
serve
phone /path/to/file.pdf
```

## 文件上限与设备管理

默认单文件上限为：手机到电脑 `1024 MiB`，电脑到手机 `4096 MiB`。两侧传输存储区
默认各限制为 `10240 MiB`，并预留至少 `512 MiB` 可用磁盘空间。修改现有配置不会
轮换证书或 Token：

```bash
cd WifiShare/server
python3 -m lss_server configure --config ./state/config.json \
  --max-upload-mb 4096 \
  --max-outbox-file-mb 8192 \
  --max-storage-mb 20480
systemctl --user restart wifishare.service
```

未使用 systemd 时，停止并重新执行 `serve` 才会应用新限制。数值单位是 MiB，且
`max-storage-mb` 应大于需要接收或排队的最大单文件。

查看和撤销已配对设备：

```bash
cd WifiShare/server
python3 -m lss_server devices list --config ./state/config.json
python3 -m lss_server devices revoke <device_id> --config ./state/config.json
python3 -m lss_server pairing --config ./state/config.json --device-name "Pixel"
```

重新配对会创建新的、默认有效期 365 天的设备 Token，不会自动撤销旧 Token。
服务端只保存新 Token 的 SHA-256 摘要，不保存其明文。手机可打开输出的
`Pairing URI` 确认配对，或在设置页编辑服务端，将 `auth_token` 的值填入“认证 Token”，
同时填写 `base_url` 和 `certificate_sha256`；复制值时不含引号，也不添加 `Bearer `。

新设备 Token 的签发和撤销对后续请求即时生效。但首次从旧版共享 Token 迁移时，
`pairing` 或 `devices migrate-legacy` 会更新磁盘配置；需要在无传输任务时再重启一次
服务，清除进程中保留的旧共享 Token。仅重启不会自动更换 Token。确认新 Token 可用后，
可通过 `devices list` 找到旧设备 ID 并撤销。

## 安全边界

- 只在可信局域网内使用，不要把 `8443/tcp` 直接映射到公网。自签名证书固定、限速和并发限制不能替代公网身份系统、防火墙与反向代理防护。
- 配对 URI 含长期设备 Token；Android 会先展示服务器地址和证书指纹供确认，但自定义 `lss://` Scheme 仍可能被其他 App 注册。只通过可信通道短时传递，配对后及时删除。
- Android 只接受严格的 HTTPS 地址，固定校验证书 SHA-256，并用 Android Keystore + AES-GCM 加密本地服务器凭据；系统备份和设备迁移不包含 App 数据。
- 服务端按设备授权 `upload`、`outbox.read`、`outbox.ack`，并实施请求超时、TLS 握手超时、每 IP 限速、并发上限、单文件上限、目录容量和最低剩余空间保护。
- 这些措施降低误配置、局域网窃听和凭据泄露后的风险，但当前版本没有经过独立渗透测试，也没有一次性配对码交换协议。

## systemd

用户级 systemd 模板位于：

```text
server/systemd/wifishare.service
```

默认安装不启用 systemd，只打印手动启动说明。需要用户级 systemd 时：

```bash
install_wifishare repair --enable_systemd
```

脚本会安装 `~/.config/systemd/user/wifishare.service`，并写入
`~/.config/wifishare/server.env`。查看状态和日志：

```bash
systemctl --user status wifishare.service
journalctl --user -u wifishare.service -f
```

## 构建

首次建立本地正式签名（只执行一次）：

```bash
cd WifiShare/android
./scripts/configure_release_signing.sh
```

生成并校验正式 APK：

```bash
./scripts/build_release.sh
```

签名材料位于 `android/.signing/` 和 `android/signing.properties`，权限必须为 `0600`，
且已被 Git 忽略。必须离线备份这两项；丢失密钥后将无法覆盖升级已安装的正式版本。
Debug 构建可使用 `gradle assembleDebug`。

## 验证状态

- Python `py_compile` 和 20 个服务端测试通过，其中覆盖 Token scope、413、429、507、损坏凭据拒绝、私有权限与临时文件清理。
- 39 个 Android JVM 测试、Debug/Release 编译、Lint 和 APK 构建通过；67 个 XML 可解析，中英文 209 个资源键一致。两类 Lint 均为 0 error、67 个非阻断 warning，其中 63 个是历史未使用资源。
- 推荐安装包：`android/WifiShare-v0.12.0-release.apk`，`6749536` 字节，SHA-256 为 `e614b29c92e0ffb24f41dbc860d213427c2b1b9024ffaeab8a244e03fa1bc5f7`。
- Release APK 的包名为 `io.iaw.lanshare`，`versionCode=21`、`versionName=0.12.0`，`debuggable=false`、`allowBackup=false`、`usesCleartextTraffic=false`，zipalign 和 APK Signature Scheme v2 校验通过。签名证书 SHA-256 为 `fa01a0db703a64fcbb03b8dcbaa32fbc7dbd88c3eeabf665ded81ce39babdaf3`。

## 2026-05-09 Android 客户端更新

- Android 客户端配置从单服务端改为多服务端列表，旧版单配置会自动迁移为第一个服务端。
- 配对链接或手动保存配置时，会新增/更新服务端并切换为当前服务端。
- 首页增加服务端下拉选择；设置页增加已保存服务端选择、设为当前、删除入口。
- 后续按实机反馈移除了下拉框：首页改为横向快捷切换按钮，设置页改为可点击服务端列表；点击服务端会把详细信息填入下方表单供编辑，保存时替换原服务端而不是生成重复配置。
- Android UI 字体从装饰性 serif/condensed 调整为系统无衬线字体，标题字号收敛，减少和系统界面的割裂感。
- 主界面和设置界面根据系统状态栏/导航栏 inset 调整 padding，避免 Android 顶部状态栏遮挡内容。
- 验证：`gradle assembleDebug` 通过；`aapt dump badging android/WifiShare-debug.apk` 确认包名 `io.iaw.lanshare`、`versionCode 3`、`versionName 0.1.2`、`INTERNET` 权限；`apksigner verify --verbose android/WifiShare-debug.apk` 确认 v2 签名通过。

## 2026-05-15 Android 客户端更新

- UI 改为更接近 macOS 原生极简质感的中性色系统：浅灰背景、白色薄边框面板、graphite 文本、system blue 操作色、低阴影和小圆角控件。
- 设置页增加 `adjustResize` 和 IME inset 处理；输入框获得焦点时会主动请求滚动区域把当前输入框移到键盘上方，避免输入法遮住正在编辑的配置项。
- 增加 Android 桌面小组件 `WifiShare 快捷组件`：显示当前服务端，支持在多个已保存服务端之间轮换切换；“接收”按钮会打开 App 并直接拉取 Linux `phone` 队列文件。
- 小组件会在 App 内切换、保存、删除服务端和配对链接保存后同步刷新。
- APK 版本更新为 `versionCode 4`、`versionName 0.1.3`，并已复制到 `android/WifiShare-debug.apk`。
- 验证：`gradle assembleDebug` 通过；`aapt dump badging android/WifiShare-debug.apk` 确认包名 `io.iaw.lanshare`、`versionCode 4`、`versionName 0.1.3`、`INTERNET` 权限和 `app-widget` 组件；`aapt dump xmltree` 确认 `WifiShareWidgetProvider`、widget provider metadata 以及 `windowSoftInputMode=adjustResize` 已进入 manifest；`apksigner verify --verbose android/WifiShare-debug.apk` 确认 v2 签名通过。当前沙箱无法启动 ADB daemon，因此未做实机安装验证。
- 发布：已通过干净临时发布工作树推送到 `https://github.com/iawnix/WifiShare` 的 `main` 分支，提交 `96abf961f863907e275869965e84b676d6751b0c`；发布范围只包含 Android 代码、最终 APK 和公共 README 更新，未包含 `server/state/`、证书、pairing 文件、local.properties 或 build cache。

## 2026-05-15 Android 小组件跟进

- 小组件“接收”按钮改为启动 `ReceiveQueueService` 前台服务，不再跳转到 App 主界面；下载状态和结果通过系统通知与 Toast 返回。
- 为 Android 14+ 的前台数据同步服务声明 `FOREGROUND_SERVICE` 和 `FOREGROUND_SERVICE_DATA_SYNC` 权限，并在 manifest 中注册 `ReceiveQueueService`。
- 小组件视觉改为更接近灵动岛的深色胶囊：黑色圆角岛、状态点、LAN 标签、深色切换按钮和蓝色接收按钮。
- App 背景从纯浅灰改回更柔和的暖灰到薄荷雾面渐变，面板颜色也从纯白收回到暖白。
- APK 版本更新为 `versionCode 5`、`versionName 0.1.4`，并已复制到 `android/WifiShare-debug.apk`。
- 验证：`gradle assembleDebug` 通过；`aapt dump badging android/WifiShare-debug.apk` 确认 `versionCode 5`、`versionName 0.1.4`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_DATA_SYNC`、`app-widget` 和 `other-services`；`aapt dump xmltree` 确认 `ReceiveQueueService`、`foregroundServiceType=dataSync`、widget provider metadata 和 `adjustResize`；`apksigner verify --verbose android/WifiShare-debug.apk` 确认 v2 签名通过。当前沙箱仍无法启动 ADB daemon，因此未做实机安装验证。
- 发布：已通过干净临时发布工作树推送到 `https://github.com/iawnix/WifiShare` 的 `main` 分支，提交 `6210613c357fcb59ffeb467f003c75f514953bac`；发布范围只包含 Android 代码、最终 APK 和公共 README 更新，未包含 `server/state/`、证书、pairing 文件、local.properties 或 build cache。

## 2026-05-15 Android 小组件兼容修复

- 修复小组件布局兼容性：把 `RemoteViews` 不可靠的普通 `<View>` 状态点替换为 `TextView`，避免部分 launcher 直接让小组件不可用。
- 小组件接收按钮改为先触发 `WifiShareWidgetProvider` 广播，再显式启动 `ReceiveQueueService`；如果服务启动失败，会通过 Toast 返回错误，而不是静默失效。
- 增加 `POST_NOTIFICATIONS` 权限，并在 App 打开时为 Android 13+ 请求通知权限，保证后台接收进度和结果通知可见。
- APK 版本更新为 `versionCode 6`、`versionName 0.1.5`，并已复制到 `android/WifiShare-debug.apk`。
- 验证：`gradle assembleDebug` 通过；`aapt dump badging android/WifiShare-debug.apk` 确认 `versionCode 6`、`versionName 0.1.5`、`POST_NOTIFICATIONS`、前台服务权限、`app-widget` 和 `other-services`；`aapt dump xmltree` 确认 `ReceiveQueueService`、`foregroundServiceType=dataSync` 和 widget provider metadata；`apksigner verify --verbose android/WifiShare-debug.apk` 确认 v2 签名通过。当前沙箱仍无法启动 ADB daemon，因此未做实机安装验证。
- 发布：已通过干净临时发布工作树推送到 `https://github.com/iawnix/WifiShare` 的 `main` 分支，提交 `e0c518e42ace6ab0cfd3bd74921e423bc0d19b15`；发布范围只包含 Android 代码、最终 APK 和公共 README 更新，未包含 `server/state/`、证书、pairing 文件、local.properties 或 build cache。

## 2026-05-25 可靠性与安全优化

- Android 关闭系统备份，避免服务端 token、证书指纹和已保存服务端配置进入设备备份。
- 普通打开 App 不再自动拉取 Linux 队列；接收只由“接收队列文件”按钮、小组件接收按钮或显式接收入口触发。
- 手机发送到电脑改为 `UploadService` 前台服务，发送过程通过系统通知展示，降低大文件发送时 Activity 被回收导致中断的风险。
- Linux 到手机的队列增加 `pending -> inflight -> ack` 领取流程；未 ack 的 inflight 文件有 lease，到期后可重新回到 pending，避免 App 和小组件并发拉取时重复接收同一个文件。
- APK 版本更新为 `versionCode 7`、`versionName 0.1.6`，并已复制到 `android/WifiShare-debug.apk`。
- 验证：`python3 -m py_compile server/lss_server/*.py` 通过；`python3 -m unittest discover -s tests -v` 通过 8 个服务端测试；`gradle assembleDebug` 通过；`aapt dump badging android/WifiShare-debug.apk` 确认 `versionCode 7`、`versionName 0.1.6`、通知和前台服务权限；`aapt dump xmltree` 确认 `allowBackup=false`、`ReceiveQueueService` 和 `UploadService` 均为 `foregroundServiceType=dataSync`；`apksigner verify --verbose android/WifiShare-debug.apk` 确认 v2 签名通过。当前环境未做 ADB 实机安装验证。

## 2026-05-30 安装脚本与极简界面

- 新增根目录 `install_wifishare` 和服务端 `server/install_wifishare`，支持 `--ip`、`--enable_systemd` / `--enable_systemed`、`--shell bash|zsh|fish`、`--relay_dir`、`repair` 和 `-h`。
- 安装脚本会初始化 `server/state/config.json`、证书、token、上传目录和 phone 队列目录；配对信息只在安装/repair 终端输出，不写入本地 `pairing.json` 或 `pairing-uri.txt`。
- 默认上传目录改为 `~/Downloads/WifiShare`；shell 环境写入 `~/.config/wifishare/env.sh` 或 `env.fish`，并在对应 shell rc 中追加 source 行。
- systemd 默认不启用；启用时安装用户级 `wifishare.service`，写入 `~/.config/wifishare/server.env`，并打印 `systemctl --user` / `journalctl --user` 管理命令。
- Android 主界面改为更紧凑的工具式布局：设置、发送、接收、启用、新增、删除、返回等操作增加图标，发送/接收按钮文案收短，首页减少说明文本。
- APK 版本更新为 `versionCode 8`、`versionName 0.1.7`，并已复制到 `android/WifiShare-debug.apk`。
- 验证：`./install_wifishare -h` 通过；隔离 HOME 的安装脚本测试确认 `~/Relay` 展开正确且未生成 `pairing.json` / `pairing-uri.txt`；`python3 -m py_compile server/lss_server/*.py server/tests/test_server.py` 通过；`python3 -m unittest discover -s tests -v` 通过 9 个服务端测试；`gradle assembleDebug` 通过；`aapt dump badging android/WifiShare-debug.apk` 确认 `versionCode 8`、`versionName 0.1.7`、通知和前台服务权限；`aapt dump xmltree` 确认 `allowBackup=false`、widget、`ReceiveQueueService`、`UploadService` 和 `foregroundServiceType=dataSync`；`apksigner verify --verbose --print-certs android/WifiShare-debug.apk` 确认 v2 签名通过。
- APK SHA-256：`b048f9ac2cb9ff819da668aef554a0d9b792081ff0c4e0ea37d7ad13e797528f`。
- 风险：当前环境未做 ADB 实机安装验证。

## 2026-05-30 Android 明暗主题与设置页优化

- App 增加手动明亮/黑暗主题切换，主题选择保存在本地设置中，首页和设置页共享。
- 主界面和设置页改为更接近 macOS 的浅灰/深灰背景、白色或深色面板、薄边框、低阴影和 system blue 操作色，强化背景与卡片层次。
- 设置页顶部保存、明暗切换、返回，以及启用、删除、新增等操作改为图标按钮；服务端列表里的启用入口也改为图标化。
- 新增运行时主题工具 `AppTheme` 和 `GradientDrawableFactory`，减少对静态浅色 drawable 的依赖。
- APK 版本更新为 `versionCode 9`、`versionName 0.1.8`，并已复制到 `android/WifiShare-debug.apk`。
- 验证：`git diff --check` 通过；`gradle assembleDebug` 通过；`aapt dump badging android/WifiShare-debug.apk` 确认包名 `io.iaw.lanshare`、`versionCode 9`、`versionName 0.1.8`、通知和前台服务权限；`apksigner verify --verbose --print-certs android/WifiShare-debug.apk` 确认 v2 签名通过。
- APK SHA-256：`fb3118e3af8ccb8bd84150e1a38029b1b6293c4229334c11f08110de11cddbe8`。
- 风险：当前环境未做 ADB 实机安装和截图验证。

## 2026-05-30 Android 启动崩溃热修复

- 修复主题层直接引用 API 30 `WindowInsetsController` 导致 Android 10 / API 29 设备启动闪退的风险，系统栏明暗设置改回 `systemUiVisibility` 兼容路径。
- APK 版本更新为 `versionCode 10`、`versionName 0.1.9`，并已复制到 `android/WifiShare-debug.apk`。
- 验证：`git diff --check` 通过；`gradle assembleDebug` 通过；`aapt dump badging android/WifiShare-debug.apk` 确认 `versionCode 10`、`versionName 0.1.9`；`apksigner verify --verbose --print-certs android/WifiShare-debug.apk` 确认 v2 签名通过。
- APK SHA-256：`8e97fde78f17ddb39825c8034f39fd8add83685ee8d9af42013e64ddf216429a`。

## 2026-05-30 Android 原生 UI 小改

- 主页发送/接收从大块文字按钮改为轻量图标 action，保留状态文本展示待发送文件和接收结果。
- 设置页收敛为服务端配置维护：选择、编辑、保存、新增、删除；不再提供“启用/当前切换”操作，当前传输目标仍在首页和小组件切换。
- 设置页保存配置不再默认切换当前目标；若编辑的是当前目标，则保留当前目标并更新其配置。
- 小组件增加接收中状态：点击接收后显示 `接收中` 和不确定进度条，接收完成后恢复。
- APK 版本更新为 `versionCode 11`、`versionName 0.2.0`，并已复制到 `android/WifiShare-debug.apk`。
- 验证：`git diff --check` 通过；`gradle assembleDebug` 通过；`aapt dump badging android/WifiShare-debug.apk` 确认 `versionCode 11`、`versionName 0.2.0`；`apksigner verify --verbose --print-certs android/WifiShare-debug.apk` 确认 v2 签名通过。
- APK SHA-256：`32b4f7854c289ef5ed486bf52ad9a2d74789842942e0a950c1a8b9d00186756a`。

## 2026-09-05 Android 桌面小组件重构

- 每个小组件实例改为独立绑定一个服务端。首次添加时必须在配置页确认；旧版小组件升级后会绑定升级时的当前服务端，之后不再跟随 App 首页切换。绑定服务端被删除时，小组件明确显示“需要重新配置”，不会静默切换目标。
- 服务端配置增加稳定 UUID。旧 `profiles_json`、`active_profile` 和单服务端字段会自动迁移并继续写回，便于旧版 APK 回退读取；Linux 服务端协议没有变化。
- 小组件提供 compact、standard、wide 三种布局。Android 12 及以上由 `RemoteViews` 响应式尺寸映射选择，Android 10/11 根据 launcher 提供的宽度选择；同时支持 DayNight 语义色和 Android 12 及以上动态系统色。
- UI 采用 Apple Human Interface Guidelines 中可迁移的清晰层级、一致性、直接反馈和克制装饰原则，同时以 Android 小组件规范为最终约束：主要动作保持 48dp 触控高度，长名称单行截断，进度和错误直接显示，不照搬 Apple 平台专属控件。
- App 与小组件统一通过非导出的 `ReceiveQueueService` 接收，并由全局互斥锁阻止并发拉取。状态机覆盖检查、文件名与字节进度、成功、空队列、忙碌、中断和结构化错误；服务异常终止后的陈旧状态会自动恢复，结果展示 30 秒后复位。
- 小组件 PendingIntent 只携带服务端 UUID 和 widget ID，不携带 URL、token 或证书指纹。Provider 和传输服务均不导出，内部完成广播使用 signature 权限；备份规则排除凭据、传输状态和小组件绑定。
- 新增 17 个 JVM 测试，覆盖配置迁移、稳定绑定、删除状态、传输状态机、超时恢复、尺寸选择、PendingIntent 唯一性、接收互斥和错误分类。
- APK 版本更新为 `versionCode 12`、`versionName 0.3.0`，已复制到 `android/WifiShare-debug.apk`。
- 验证：`testDebugUnitTest`、`compileDebugKotlin`、`lintDebug`、`assembleDebug`、XML 解析和 `git diff --check` 通过；lint 为 0 error、27 个非阻断 warning。`aapt dump badging` 和 `aapt dump xmltree` 确认版本、权限、非导出组件、配置 Activity、响应式 widget metadata 及备份规则进入 APK；`apksigner verify --verbose --print-certs` 确认 v2 签名通过。
- APK 大小：`2612377` 字节；SHA-256：`f87673ea4f4da6023803a65b0d0ba8358aab50bb5ac4afbf4cd9b9d59b4f7b2f`。
- 实机风险：当前 ADB 没有连接设备，尚未验证 API 29/35 launcher、三档宽度、明暗/动态色、200% 字体、超长服务端名称、通知权限拒绝和接收服务被系统终止场景。

## 2026-09-05 Android 非调试发布包

- 新增 `android/WifiShare-v0.3.0-release.apk`：由 Gradle `release` 变体构建，manifest 不包含 `debuggable` 或 `testOnly`，版本为 `versionCode 12`、`versionName 0.3.0`。
- 为允许直接覆盖仓库此前分发的 debug APK并保留配置，release APK沿用历史安装证书，证书 SHA-256 为 `f0651b1fbb55e7300cefd509094eb2006899878474aa0b7482415bbc127504b9`。这是迁移兼容签名，不是应用商店级发布证书；正式对外发布前应建立受保护的 release keystore 和密钥备份流程。
- 验证：`lintRelease`、`assembleRelease`、`zipalign -c`、`aapt dump badging`、`aapt dump xmltree` 和 `apksigner verify --verbose --print-certs` 通过；APK使用 v3 签名，且与历史 APK证书一致。当前 ADB 没有连接设备，未执行覆盖安装测试。
- APK 大小：`2251841` 字节；SHA-256：`ceb9fb544af93c170f536d4c6aa6d078230022b1b4e8a338dc539dde77679172`。

## 2026-09-05 Android 液态玻璃界面

- 主界面改为清透与深色两套液态玻璃视觉：多色背景、半透明渐变表面、细高光边缘、柔和阴影和蓝绿强调色由统一主题层生成，不改变上传、接收或配对协议。
- 发送与接收改为带图标的 56dp 主操作；当前服务端、待发送内容、传输状态和不确定进度集中在两块玻璃面板中。设置页与小组件配置页同步使用同一视觉语言。
- compact、standard、wide 三档桌面小组件改为透明玻璃渐变、品牌色图标面和 48dp 以上接收操作；Android 12 及以上继续使用动态系统强调色。受 `RemoteViews` 平台限制，小组件使用透明分层与高光模拟折射，不使用不可用的实时背景模糊。
- 启动图标更新为蓝绿配色，并补充 monochrome 图标入口；所有真实操作保持至少 48dp，组件辅助文字不低于 11sp，长服务端名称继续单行截断。
- APK 版本更新为 `versionCode 13`、`versionName 0.4.0`。推荐安装包为 `android/WifiShare-v0.4.0-release.apk`。
- 验证：17 个 JVM 单元测试通过；`compileDebugKotlin`、`lintDebug`、`lintRelease`、`assembleDebug`、`assembleRelease`、XML 解析和 `git diff --check` 通过。Release Lint 为 0 error、22 个既有非阻断 warning。
- Release APK 的 `debuggable=false`，不含 `testOnly`，zipalign 校验通过，v3 签名有效；签名证书 SHA-256 为 `f0651b1fbb55e7300cefd509094eb2006899878474aa0b7482415bbc127504b9`，与上一版一致，可覆盖安装并保留现有配置。
- APK 大小：`2268592` 字节；SHA-256：`8eb2e686c8ff60e6bb76cca8a324402cfb7dd0cf2134296a2cf73220fbd950e8`。
- 实机风险：当前 ADB 没有连接设备，尚未完成 API 29/35 实机截图、不同 launcher 小组件透明度、200% 字体和覆盖安装验证。

## 2026-09-05 Android 双语与发送任务管理

- 主界面按用户任务拆成首页、发送前确认和发送中/结果三个独立状态，不再把服务端、收发操作、文件列表和传输结果堆在同一个页面。视觉延续灰蓝背景、半透明玻璃表面、细高光和系统蓝主操作；设置页改为设置首页、语言页和独立服务端编辑页。
- App 支持跟随系统、简体中文 `zh-CN` 和 English `en`。Activity、前台服务通知、Toast、桌面小组件和小组件配置页统一使用当前 App 语言；Android 13 及以上同时声明系统级应用语言配置。
- 系统分享进入 App 后先显示完整待发送列表，包括文件名、类型和大小。发送前可逐项移除，或取消整份草稿；这些操作不会删除源文件，也不会发起网络请求。Activity 重建时会恢复仍可访问的 URI 草稿。
- 点击发送后冻结文件顺序与目标服务端，并以独立 `operationId`、持久状态和进程内互斥锁管理任务。页面和通知栏均可“停止发送”：客户端会关闭当前输入流、输出流和 HTTPS 连接，并跳过后续文件；已经完整提交到电脑的文件会保留，界面会显示实际完成数。
- 上传状态机覆盖预处理、发送、停止请求、成功、失败、已停止、忙碌和进程中断。旧任务的迟到广播不会覆盖当前任务；服务运行期间每 30 秒更新心跳，避免大文件 SHA-256 预处理被误判为中断。
- APK 版本更新为 `versionCode 14`、`versionName 0.5.0`。推荐安装包为 `android/WifiShare-v0.5.0-release.apk`，包名为 `io.iaw.lanshare`。
- 验证：30 个 Android JVM 单元测试、9 个 Python 服务端测试、`compileDebugKotlin`、`lintDebug`、`lintRelease`、`assembleDebug`、`assembleRelease`、XML 解析、双语资源名称比对和 `git diff --check` 通过。Debug/Release lint 均为 0 error、39 个非阻断 warning。
- Release manifest 不含 `debuggable` 或 `testOnly`；zipalign 校验通过，v3 签名有效。签名证书 SHA-256 为 `f0651b1fbb55e7300cefd509094eb2006899878474aa0b7482415bbc127504b9`，与 `0.4.0` 一致，可覆盖安装并保留现有配置。
- Release APK 大小：`6586948` 字节；SHA-256：`8c24c1d280d3ce3d1c283948ab956e8f5c690552ec182eb42643ea82233260ec`。
- 实机风险：当前 ADB 没有连接设备，尚未执行覆盖安装、真机语言切换、长文件中途停止、通知栏停止操作、不同字体缩放和 launcher 小组件视觉验证。

## 2026-09-05 Android Hacker 主题与小组件跟随

- App 主界面重排为紧凑的 Hacker 工具布局：品牌头、节点选择条、`RX / QUEUE` 接收区、`TX /` 分享入口和状态驱动的连接提示；卡片、输入框和按钮圆角统一收敛，减少此前内容堆叠和过度装饰。
- 主题选择升级为 `SYSTEM / LIGHT / DARK` 三态，默认跟随 Android 系统。Activity 在创建界面前应用 DayNight 模式，设备选择由自定义底部面板承载，避免深色界面弹出白色系统菜单。
- 桌面小组件遵循 App 的主题设置：`SYSTEM` 使用系统 Day/Night 资源，显式浅色或深色选择使用对应的固定资源；Android 12 及以上的进度条、文字、图标和操作面也显式应用匹配颜色。
- 小组件配置页改为紧凑标题、实时预览、服务端选择列表、绑定说明和保存/管理操作。每个小组件继续独立绑定服务端，切换 App 当前节点不会暗中改变既有小组件目标。
- 中英文切换、发送前逐项移除、取消整份草稿、发送中停止和已完成文件保留语义保持有效；Linux 服务端协议未改变。
- APK 版本更新为 `versionCode 15`、`versionName 0.6.0`。推荐安装包为 `android/WifiShare-v0.6.0-release.apk`，包名为 `io.iaw.lanshare`。
- 验证：33 个 Android JVM 单元测试、9 个 Python 服务端测试、Python `py_compile`、Debug/Release 构建、XML 解析、双语资源名称比对和 `git diff --check` 通过。Release Lint 为 0 error、45 个非阻断 warning，其中主要是历史未用资源、目标 SDK、旧 mipmap qualifier 和证书指纹校验实现提示。
- Release manifest 不含 `debuggable` 或 `testOnly`；zipalign 校验通过，v3 签名有效。签名证书 SHA-256 为 `f0651b1fbb55e7300cefd509094eb2006899878474aa0b7482415bbc127504b9`，与 `0.5.0` 一致，可覆盖安装并保留现有配置。
- Release APK 大小：`6636887` 字节；SHA-256：`df7009c1baca1d9959161d4d21d5d329337aef7a525f88183cc306d8967e0aae`。
- 实机风险：当前没有可用 ADB 设备或 Android 模拟器，尚未完成覆盖安装、真机截图、不同系统主题切换、字体缩放和不同 launcher 上的小组件视觉验证。

## 2026-09-05 Android 0.7.0 统一节点与几何交互

- App 与所有桌面小组件改为共用同一个当前服务端状态；在 App 或小组件中切换节点会同步生效。小组件的服务端区域会打开与 App 相同的选择面板，接收请求不再保存或携带独立服务端 ID。
- 首页重排为紧凑品牌栏、当前节点行、中央接收命令和分享入口。接收区使用 68dp 图标主操作、目标角框与信号矩阵建立视觉重心，减少大段标签、超宽按钮和重复说明文字。
- 分享草稿保留发送前管理：可取消整份任务、逐项移除文件，移除最后一项后自动返回首页；服务器切换、取消和文件操作统一使用紧凑图标按钮。发送中/结果页使用较小的停止或完成操作，避免按钮抢占内容空间。
- 服务端选择器使用底部面板进出动画、现代返回手势处理和最高 360dp 的可滚动列表；设置子页使用协调的进入/退出动画。深色、浅色和跟随系统三种主题继续有效，深色节点切换面板不再出现白色底面。
- compact、standard、wide 三档小组件统一为居中的 48dp 图标操作；玻璃中性色表面以绿色终端强调色、线框和几何元素建立 Hacker 风格，同时保留可读性和触控尺寸。
- APK 版本更新为 `versionCode 16`、`versionName 0.7.0`。推荐安装包为 `android/WifiShare-v0.7.0-release.apk`，包名为 `io.iaw.lanshare`。
- 验证：30 个 Android JVM 单元测试、9 个 Python 服务端测试和 Python `py_compile` 通过；`compileDebugKotlin`、`lintDebug`、`lintRelease`、`assembleDebug`、`assembleRelease`、Android XML 解析、双语 string/plural 资源名称比对和 `git diff --check` 通过。Debug/Release lint 均为 0 error、63 个非阻断 warning。
- Release manifest 不含 `debuggable` 或 `testOnly`；zipalign 校验通过，APK Signature Scheme v3 签名有效。签名证书 SHA-256 为 `f0651b1fbb55e7300cefd509094eb2006899878474aa0b7482415bbc127504b9`，与 `0.6.0` 一致，可覆盖安装并保留现有配置。证书名称沿用历史迁移密钥中的 `Android Debug`，正式公开分发仍应迁移到受保护的 release keystore。
- Release APK 大小：`6616184` 字节；SHA-256：`eb64dee27d744c836967a5d185ef4a5345e728db49d699f36a3d6eb17648bab6`。
- 实机风险：当前没有可用 ADB 设备或 Android 模拟器，尚未执行覆盖安装、真机截图、字体缩放、系统主题联动和不同 launcher 上的小组件视觉验证。

## 2026-09-05 Android 0.8.0 中性布局与紧凑小组件

- App 的浅色与深色主题统一为中性灰玻璃表面，主要操作固定使用系统蓝；绿色只表示在线或成功，不再参与背景、选中行和装饰渐变。
- 首页收敛为标题、当前服务端和中央接收动作。移除常驻配对/接收提示、终端品牌图标、贯穿竖线、菱形和九宫格装饰；保留四段短角框，并将接收按钮缩为 `64dp`。传输进度与结果只在有上下文时出现。
- compact、standard、wide 三档桌面小组件改为约 `72dp` 的单行布局。移除重复 App 标识和空闲 `Ready` 文案，隐藏状态、详情和进度时使用 `GONE`；进度条覆盖在组件底边，不再预留整行高度。
- 小组件继续与 App 使用同一个当前服务端，点击服务端区域进入同一服务端选择界面；跟随系统、固定浅色和固定深色三种主题策略继续有效。Android 12 及以上也固定使用蓝色操作色，避免壁纸动态色把 Light 主题变成绿色。
- 中英文切换、发送前逐项移除或取消草稿、发送中停止、已完成文件保留语义、接收状态机和 Linux 服务端协议均保持不变。
- APK 版本更新为 `versionCode 17`、`versionName 0.8.0`，包名为 `io.iaw.lanshare`。
- 验证：30 个 Android JVM 单元测试、9 个 Python 服务端测试和 Python `py_compile` 通过；`compileDebugKotlin`、`lintDebug`、`lintRelease`、`assembleDebug`、`assembleRelease`、Android XML 解析、双语资源名称比对和 `git diff --check` 通过。Debug/Release lint 均为 0 error、61 个非阻断 warning。
- Release APK：`android/WifiShare-v0.8.0-release.apk`，`6607654` 字节，SHA-256 为 `52bb33439a100397a46aca937a7c5e6eb585e551f22cd40a794656f961379e28`。Manifest 不含 `debuggable` 或 `testOnly`，zipalign 校验通过，APK Signature Scheme v3 签名有效。
- Debug APK：`android/WifiShare-debug.apk`，`7818936` 字节，SHA-256 为 `01bc4990c0b942c8807c835469f6fc7c4700d465ad711f0f32da40aa4591377e`，保留 `debuggable` 标志并通过 APK Signature Scheme v3 校验。
- 两个 APK 均沿用历史迁移证书，证书 SHA-256 为 `f0651b1fbb55e7300cefd509094eb2006899878474aa0b7482415bbc127504b9`，可覆盖 0.7.0 并保留现有配置。证书名称仍为 `Android Debug`，正式商店分发前应迁移到受保护的 release keystore。
- 实机风险：当前没有可用 ADB 设备或 Android 模拟器，尚未执行覆盖安装、真机截图、系统主题切换、字体缩放和不同 launcher 上的小组件视觉验证。

## 2026-09-05 Android 0.9.0 任务化主页

- 主页移除 `220–280dp` 的中央命令舞台、四角框和单独下载圆钮，改为顶部当前服务端与底部 `72dp` 玻璃操作坞。发送、接收成为两个等权核心动作，中间空间只在发生传输时承载状态，不再使用无功能装饰填充。
- 主页“发送”使用 Android 系统文件选择器，可一次选择多个文件；选择结果继续进入既有发送草稿，可更换目标服务端、逐项移除或取消整份任务，未确认前不会发起网络请求。
- 接收进度和结果改为按需展开的紧凑状态面板；接收按钮在检查队列或传输中直接更新文案。服务端圆点改为蓝色当前目标标记，不再把“已配置”错误表现为绿色在线状态。
- 标题栏设置、草稿关闭与服务器切换使用无底色图标按钮，保留 `44dp` 触控区；玻璃高光和阴影只用于服务端、操作坞与真实状态容器。中英文、跟随系统/浅色/深色主题、上传取消、接收状态机、小组件和 Linux 协议均保持不变。
- APK 版本更新为 `versionCode 18`、`versionName 0.9.0`，包名为 `io.iaw.lanshare`。推荐安装包为 `android/WifiShare-v0.9.0-release.apk`。
- 验证：30 个 Android JVM 单元测试、`compileDebugKotlin`、`compileReleaseKotlin`、`lintDebug`、`lintRelease`、`assembleDebug`、`assembleRelease`、Android XML 解析、双语资源名称比对和 `git diff --check` 通过。Debug/Release lint 均为 0 error、62 个非阻断 warning。
- Release APK：`android/WifiShare-v0.9.0-release.apk`，`6607654` 字节，SHA-256 为 `9d6d7c7dde8ba5f397584db844559b67cc2a5a40bfb8806d571dd04f3e0ad88d`。Manifest 不含 `debuggable` 或 `testOnly`，zipalign 校验通过，APK Signature Scheme v3 签名有效。
- Debug APK：`android/WifiShare-debug.apk`，`7818936` 字节，SHA-256 为 `a2c7aaf566ec714f3fa74cd198a9c39e62d3f8bba19b35ab0089d28f8f903264`，保留 `debuggable` 标志并通过 APK Signature Scheme v3 校验。
- 两个 APK 沿用历史迁移证书，证书 SHA-256 为 `f0651b1fbb55e7300cefd509094eb2006899878474aa0b7482415bbc127504b9`，可覆盖 0.8.0 并保留现有配置。证书名称仍为 `Android Debug`，正式商店分发前应迁移到受保护的 release keystore。
- 实机风险：当前没有可用 ADB 设备或 Android 模拟器，尚未执行覆盖安装、系统文件选择器实机回传、真机截图、字体缩放和系统主题切换验证。

## 2026-09-05 Android 0.10.0 多服务端首页与新品牌

- App 标题栏、普通自适应图标和 Android 13+ monochrome 主题图标统一使用用户提供的新 WifiShare 飞鸟文件夹 Logo；首页使用透明裁切版本，避免把原图白底带入深色主题。
- 首页增加固定高度的两行服务端架：计数胶囊、上下分割线、紧凑服务端卡片和常驻新增入口形成稳定的信息区，在常见手机宽度上可同时看到约四个服务端。服务端顺序不会因切换重排，横向滑动只浏览，点击才切换；选中项使用蓝色勾选胶囊，并在超出视口时平滑滚动到可见区域。
- 服务端切换统一遵循同一策略：点击当前服务端不产生状态变化；空闲时允许切换；上传、接收或接收服务待启动期间禁止改变目标。首页管理入口、发送草稿目标入口和新增入口均使用同一锁定检查，关闭了点击接收到前台服务写入状态之间的短暂竞态。
- 首页保留底部发送/接收双操作坞，传输状态只在有上下文时展开。较矮屏幕会改为纵向滚动，不压缩服务器架或遮挡底部操作；中英文、系统/浅色/深色主题、小组件和 Linux 服务端协议保持不变。
- APK 版本更新为 `versionCode 19`、`versionName 0.10.0`，包名为 `io.iaw.lanshare`。推荐安装包为 `android/WifiShare-v0.10.0-release.apk`。
- 验证：33 个 Android JVM 单元测试全部通过；`compileDebugKotlin`、`compileReleaseKotlin`、`lintDebug`、`lintRelease`、`assembleDebug`、`assembleRelease`、Android XML 解析、中英文资源名称比对和 `git diff --check` 通过。Debug/Release Lint 均为 0 error、64 个非阻断 warning，且没有 warning 指向本轮首页、Logo 或服务器切换文件。
- Release APK：`android/WifiShare-v0.10.0-release.apk`，`6705871` 字节，SHA-256 为 `ed35b0b3ad9631348ebc7d28eeee20f6edf6c7d5cbb4d2a75e36efa70a7f6e1a`。Manifest 不含 `debuggable` 或 `testOnly`，zipalign 校验通过，APK Signature Scheme v3 签名有效。
- Debug APK：`android/WifiShare-debug.apk`，`7925420` 字节，SHA-256 为 `1cf11fc97c00cd22766aae5a7be946fc2df000aaa55b5cb4c07762eebd8e46c8`，保留 `debuggable` 标志并通过 APK Signature Scheme v3 校验。
- 两个 APK 沿用历史迁移证书，证书 SHA-256 为 `f0651b1fbb55e7300cefd509094eb2006899878474aa0b7482415bbc127504b9`，可覆盖 0.9.0 并保留现有配置。证书名称仍为 `Android Debug`，正式商店分发前应迁移到受保护的 release keystore。
- 实机风险：当前没有可用 ADB 设备或 Android 模拟器，尚未执行覆盖安装、真机触摸滑动仲裁、超长服务端名称、字体缩放、不同 launcher 图标裁切和系统主题切换验证。

## 2026-09-06 Android 0.11.0 完整界面与小组件收敛

- 首页移除突兀的品牌标题，改为当前服务端地址、固定四槽宽度的服务端区、最近三条传输记录和底部发送/接收操作坞。新增服务端入口固定在第四个位置；超过三个服务端后可横向浏览，滑动不改变选择，切换也不重排列表。
- 发送前确认页支持逐项移除和取消整份草稿；发送开始后冻结目标与文件顺序，并可从页面或通知停止。停止不会删除源文件，也不会撤回已由电脑完整接收的文件。
- 设置页收敛为服务端、语言、外观、保存位置和小组件分组；语言支持跟随系统、简体中文和 English，主题支持跟随系统、浅色和深色。App、通知、Toast 与 Widget 共用当前语言，Widget 按 App 的主题策略渲染。
- 服务端选择器改为标准全屏页面。App 与所有 Widget 共用同一个当前服务端，任何一处确认切换都会同步刷新；上传或接收期间禁止更改目标。
- Widget 收敛为同一 Provider 下的紧凑与展开两档，对应 `2×1` 和 `4×1` 使用场景。最小尺寸为 `110×56dp`，上下 padding 为 `4dp`，主要操作为 `48dp`；点击主体可重新配置，状态覆盖未配置、检查、接收、成功、空队列、忙碌、中断与结构化错误。
- 新增本地最近传输历史，最多保存 20 条、首页显示 3 条；只记录方向、服务端标识与名称、数量、结果和时间，不记录 token、证书、文件名或文件内容。Linux 服务端协议没有变化。
- APK 版本为 `versionCode 20`、`versionName 0.11.0`，包名为 `io.iaw.lanshare`。推荐安装包为 `android/WifiShare-v0.11.0-release.apk`。
- 验证：35 个 Android JVM 单元测试通过；`compileDebugKotlin`、`lintDebug`、`lintRelease`、`assembleDebug`、`assembleRelease`、67 个 Android XML 文件解析、中英文 203 个资源键比对和 `git diff --check` 通过。Debug/Release Lint 均为 0 error、68 个非阻断 warning，其中 64 个是历史未用资源，其余为目标 SDK、旧 mipmap qualifier、证书固定实现和布局合并建议。
- Release APK：`android/WifiShare-v0.11.0-release.apk`，`6776095` 字节，SHA-256 为 `1c4002d72993dc2242dd1ded159d920fb89bd3d85d3c77ebea3c4352e13ad8f6`。Manifest 不含 `debuggable` 或 `testOnly`，zipalign 校验通过，APK Signature Scheme v3 签名有效。
- Debug APK：`android/WifiShare-debug.apk`，`8003982` 字节，SHA-256 为 `31c5dd63be3c0ed54942081bb539bb13954a7c349e753cf608c6cdd3192c42b6`，保留 `debuggable` 标志并通过 APK Signature Scheme v3 校验。
- 两个 APK 沿用历史迁移证书，证书 SHA-256 为 `f0651b1fbb55e7300cefd509094eb2006899878474aa0b7482415bbc127504b9`，可覆盖 `0.10.0` 并保留现有配置。证书名称仍为 `Android Debug`，正式商店分发前应迁移到受保护的 release keystore。
- 实机风险：当前没有可用 ADB 设备或 Android 模拟器，尚未执行覆盖安装、真机截图、触摸滑动仲裁、系统语言/主题切换、字体缩放和不同 launcher 上的 Widget 视觉验证。

## 2026-09-06 0.12.0 安全与容量控制

- 服务端改为每设备独立 Token，只持久化 SHA-256 摘要，并支持 scope、有效期、撤销和旧 Token 迁移；匿名健康检查不再返回服务器名称或地址。
- 增加 TLS 握手/请求超时、并发限制、每 IP 限速、上传与队列单文件上限、目录总容量和最低剩余空间保护；上传临时文件与队列文件使用私有权限和原子写入，异常路径统一清理。
- `configure` 可在不轮换证书或 Token 的情况下调整限制；`devices list|revoke|migrate-legacy` 用于设备凭据管理。安装器在启用 systemd 时会明确重启服务，使新配置立即生效。
- Android 只接受严格 HTTPS 配置，确认配对后才保存；服务器凭据使用 Android Keystore + AES-GCM 加密，旧明文配置自动迁移删除，备份规则排除全部 App 数据。HTTP 413 与 507 会分别显示单文件超限和服务端空间不足。
- Release 改用独立的 `WifiShare Release` 证书，APK 为 `versionCode 21`、`versionName 0.12.0`。由于签名与此前 Debug/迁移证书不同，首次安装必须卸载旧版并重新配对。
- 已完成 20 个 Python 服务端测试和 39 个 Android JVM 测试；最终 Lint、APK 大小、SHA-256、Manifest 与签名结果见上方“验证状态”。当前仍未做 Android 真机安装、Keystore 迁移实测、恶意局域网压测或独立渗透测试。
