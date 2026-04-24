# WifiShare

## 目标

WifiShare 是一个局域网加密文件传输原型，用于 Samsung S23 和 Linux 电脑之间互传文件。

- 手机到电脑：在 Android 系统分享菜单中选择 `WifiShare`，文件通过 HTTPS 上传到 Linux。
- 电脑到手机：Linux 端用 `phone <文件>` 加入队列，手机端点击“接收队列文件”拉取。
- Linux 端使用 Python；Android 端使用 Kotlin 原生分享入口。

## 目录

- `server/`：Python HTTPS 服务端，负责接收手机上传、维护发给手机的队列。
- `android/`：Android App 源码。
- `android/WifiShare-debug.apk`：最终保留的 debug APK。

## 快速使用

1. 启动 Linux 服务端：

   ```bash
   cd WifiShare/server
   python -m lss_server serve --config ./state/config.json
   ```

   如果还没有初始化过，先运行：

   ```bash
   python -m lss_server init \
     --state-dir ./state \
     --server-name kali-host \
     --advertise-host 192.168.31.29
   ```

   不要用 `127.0.0.1`，要填手机能访问到的 Linux 局域网 IP。

2. 安装 Android APK：

   ```text
   WifiShare/android/WifiShare-debug.apk
   ```

3. 配对手机：

   ```bash
   cd WifiShare/server
   python -m lss_server pairing --config ./state/config.json --write
   cat state/pairing-uri.txt
   ```

   把 `state/pairing-uri.txt` 做成二维码或发送到手机打开。也可以在 App 的“设置”页手动填写 `Base URL`、`Auth token`、`Cert SHA-256`。

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
. env.sh
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

Android 构建命令：

```bash
cd WifiShare/android
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export ANDROID_HOME=/home/iaw/soft/android/sdk
export ANDROID_SDK_ROOT=/home/iaw/soft/android/sdk
export GRADLE_USER_HOME=/home/iaw/soft/gradle-home
/home/iaw/soft/gradle/gradle-8.9/bin/gradle assembleDebug
```

构建后只保留源码和最终 APK；Gradle/build 临时产物应清理。

## 验证状态

- Python 服务端测试通过：`python3 -m unittest discover -s tests -v`
- Android debug 构建通过。
- APK 已复制为：`android/WifiShare-debug.apk`
