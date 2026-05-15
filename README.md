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
- `android/WifiShare-debug.apk`：最终保留的 debug APK。

## 另一台电脑安装使用

新电脑不要直接复用旧手机配置。每台电脑应重新生成自己的 `state/config.json`、证书、token 和配对链接。

1. 获取代码：

   ```bash
   git clone https://github.com/iawnix/WifiShare.git
   cd WifiShare/server
   ```

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

   选择手机能访问到的地址，例如 `192.168.1.50`，不要用 `127.0.0.1`。

4. 初始化新电脑服务端：

   ```bash
   python3 -m lss_server init \
     --state-dir ./state \
     --server-name WifiShare \
     --advertise-host 192.168.1.50
   ```

5. 启动服务：

   ```bash
   ./serve
   ```

   或启用当前 shell 的短命令：

   ```bash
   . ./env.sh
   serve
   ```

   如果使用 fish shell：

   ```fish
   source ./env.fish
   serve
   ```

6. 重新配对手机：

   ```bash
   python3 -m lss_server pairing --config ./state/config.json --write
   cat state/pairing-uri.txt
   ```

   把 `pairing-uri.txt` 做成二维码，或把链接发到手机打开。手机端会保存新电脑的 `Base URL`、`Auth token` 和证书指纹，并自动切换到这个新服务端；旧服务端配置会保留在手机端列表里。

7. 使用：

   ```bash
   ./phone /path/to/file.pdf
   ```

   然后在手机 App 首页点击“接收队列文件”。手机发电脑时，先在首页下拉框选择目标服务端，再在 Android 分享菜单选择 `WifiShare`。

可选：设置电脑接收目录。

```bash
export LAN_SECURE_SHARE_DOWNLOAD_DIR="$HOME/Downloads/WifiShare"
```

## 本机快速使用

1. 启动 Linux 服务端：

   ```bash
   cd WifiShare/server
   python -m lss_server serve --config ./state/config.json
   ```

   如果还没有初始化过，先运行：

   ```bash
   python -m lss_server init \
     --state-dir ./state \
     --server-name linux-host \
     --advertise-host 192.168.1.50
   ```

   不要用 `127.0.0.1`，要填手机能访问到的 Linux 局域网 IP。

2. 安装 Android APK：

   ```text
   android/WifiShare-debug.apk
   ```

3. 配对手机：

   ```bash
   cd WifiShare/server
   python -m lss_server pairing --config ./state/config.json --write
   cat state/pairing-uri.txt
   ```

   把 `state/pairing-uri.txt` 做成二维码或发送到手机打开。也可以在 App 的“设置”页手动填写 `Base URL`、`Auth token`、`Cert SHA-256`。手机端支持保存多个服务端，首页下拉框和设置页都可以切换当前连接的服务端。

4. 手机发送到电脑：

   在手机文件管理器或相册里点击系统分享，选择 `WifiShare`，再点“发送选中文件”。

5. 电脑发送到手机：

   ```bash
   cd WifiShare/server
   ./phone /path/to/file.pdf
   ```

   然后在手机 App 首页点击“接收队列文件”。

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

## systemd

用户级 systemd 模板位于：

```text
server/systemd/wifishare.service
```

安装前请先检查 `server/systemd/server.env.example` 里的路径。服务安装属于仓库外操作，默认不自动执行。

## 构建

Android 构建命令示例：

```bash
cd WifiShare/android
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export ANDROID_HOME=/path/to/android/sdk
export ANDROID_SDK_ROOT="$ANDROID_HOME"
gradle assembleDebug
```

构建后只保留源码和最终 APK；Gradle/build 临时产物应清理。

## 验证状态

- Python 服务端测试通过：`python3 -m unittest discover -s tests -v`
- Android debug 构建通过。
- APK 已复制为：`android/WifiShare-debug.apk`

## 2026-05-09 Android 客户端更新

- Android 客户端配置从单服务端改为多服务端列表，旧版单配置会自动迁移为第一个服务端。
- 配对链接或手动保存配置时，会新增/更新服务端并切换为当前服务端。
- 首页增加服务端下拉选择；设置页增加已保存服务端选择、设为当前、删除入口。
- 后续按实机反馈移除了下拉框：首页改为横向快捷切换按钮，设置页改为可点击服务端列表；点击服务端会把详细信息填入下方表单供编辑，保存时替换原服务端而不是生成重复配置。
- Android UI 字体从装饰性 serif/condensed 调整为系统无衬线字体，标题字号收敛，减少和系统界面的割裂感。
- 主界面和设置界面根据系统状态栏/导航栏 inset 调整 padding，避免 Android 顶部状态栏遮挡内容。
- 验证：`gradle assembleDebug` 通过；`aapt dump badging android/WifiShare-debug.apk` 确认包名 `io.iaw.lanshare`、`versionCode 3`、`versionName 0.1.2`、`INTERNET` 权限；`apksigner verify --verbose android/WifiShare-debug.apk` 确认 v2 签名通过。

## 2026-05-15 Android 客户端更新

- UI 调整为更接近 macOS 原生极简质感：浅灰背景、白色薄边框面板、graphite 文本、system blue 操作色、低阴影和小圆角控件。
- 设置页增加 `adjustResize` 和 IME inset 处理；输入框获得焦点时会主动请求滚动区域把当前输入框移到键盘上方，避免输入法遮住正在编辑的配置项。
- 增加 Android 桌面小组件 `WifiShare 快捷组件`：显示当前服务端，支持在多个已保存服务端之间轮换切换；“接收”按钮会打开 App 并直接拉取 Linux `phone` 队列文件。
- 小组件会在 App 内切换、保存、删除服务端和配对链接保存后同步刷新。
- APK 版本更新为 `versionCode 4`、`versionName 0.1.3`，并已复制到 `android/WifiShare-debug.apk`。
- 验证：`gradle assembleDebug` 通过；`aapt dump badging android/WifiShare-debug.apk` 确认包名 `io.iaw.lanshare`、`versionCode 4`、`versionName 0.1.3`、`INTERNET` 权限和 `app-widget` 组件；`aapt dump xmltree` 确认 `WifiShareWidgetProvider`、widget provider metadata 以及 `windowSoftInputMode=adjustResize` 已进入 manifest；`apksigner verify --verbose android/WifiShare-debug.apk` 确认 v2 签名通过。当前环境无法启动 ADB daemon，因此未做实机安装验证。
