# 开发与发布

[返回项目首页](../README.md) · [更新日志](../CHANGELOG.md) · [历史验证记录](archive/development-history.md)

本页记录可复现的开发步骤，不代表已执行发布。构建、提交、推送、发布 APK 和部署服务端是不同操作。
服务端与 Android 的构建互不依赖；修改 Android UI 不要求重装服务端。

## 代码与工具链

| 路径 / 组件 | 用途 |
| --- | --- |
| `server/lss_server/` | Python HTTPS 服务、设备凭据与文件队列 |
| `server/tests/` | 服务端 unittest 测试 |
| `android/app/src/main/` | Kotlin Activity、前台服务、小组件与 Android 资源 |
| `android/app/src/test/` | JVM 逻辑测试与 Robolectric Activity 回归 |
| `android/scripts/` | Release 签名初始化与构建校验 |
| `docs/images/` | 从当前代码生成并经检查的文档界面预览 |

Android 当前使用 Kotlin 2.0.21、Android Gradle Plugin 8.7.3、Gradle 8.9；
`compileSdk` / `targetSdk` 为 35，`minSdk` 为 29。
测试使用 Robolectric 4.16.1 和 Android 16 / API 36 框架运行库，要求 JDK 21。
API 36 测试运行库不表示 App 的 targetSdk 已升级到 36，也不会打入 APK。

以下 Android 步骤以 Linux + Bash 为例。需要 JDK 21、Gradle 8.9、OpenSSL、
Android SDK Platform 35 和兼容的 Build Tools（包含 `aapt`、`apksigner`、`zipalign`）。
仓库目前没有 Gradle Wrapper，需自行准备 Gradle，不能使用不存在的 `./gradlew`。

## Android 环境

进入仓库的 `android/`，将下列三个工具链路径替换为自己的安装路径：

```bash
cd /path/to/WifiShare/android
export JAVA_HOME="/path/to/jdk-21"
export ANDROID_HOME="/path/to/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export GRADLE_BIN="/path/to/gradle-8.9/bin/gradle"
export GRADLE_USER_HOME="$PWD/.gradle-local"
export ANDROID_USER_HOME="$PWD/.android-user"
export PATH="$JAVA_HOME/bin:$PATH"

java -version
"$GRADLE_BIN" --version
```

后续 Android 命令均在这个目录、同一个已设置环境的终端执行。
Release 脚本带有维护者机器的默认路径；其他机器必须显式设置上述变量。
若存在 `local.properties`，也要核对其中 `sdk.dir`，不要提交机器专属路径。

首次构建需要能访问 Google Maven 和 Maven Central。Gradle 会把 API 36 测试运行库
下载到项目缓存并复制到 `app/build/robolectric-sdk/`；Robolectric 本身使用离线解析，
不意味着首次 Gradle 构建可以完全断网。

## 测试与 Debug

```bash
"$GRADLE_BIN" --no-daemon -Pkotlin.compiler.execution.strategy=in-process \
  testDebugUnitTest lintDebug assembleDebug
```

常用输出：

- APK：`app/build/outputs/apk/debug/app-debug.apk`。
- 测试：`app/build/reports/tests/testDebugUnitTest/index.html`。
- Lint：`app/build/reports/lint-results-debug.html`。

Debug 包仅用于开发，不能假定它可覆盖正式签名版本。
仓库根下的历史 `android/WifiShare-debug.apk` 不会被上述命令自动刷新。

<a id="signing"></a>
## Release 签名与构建

维护已有正式版本时，必须恢复原发布密钥与对应配置，不能重新生成一个同名密钥冒充原签名。
只有建立自己的独立签名时，才运行一次：

```bash
./scripts/configure_release_signing.sh
```

脚本会创建 `.signing/wifishare-release.p12` 与 `signing.properties`，已有文件时拒绝覆盖。
这两份文件必须离线安全备份：目录权限为 `0700`，密钥与属性文件为 `0600`，均不得提交到 Git。
丢失密钥将无法覆盖升级原签名版本；自己生成的证书即使名称相同，指纹也不同。

在签名配置就绪后构建：

```bash
./scripts/build_release.sh
```

脚本依次执行 `testDebugUnitTest`、`lintRelease`、`assembleRelease`，
并检查包名、非 Debug 属性、非 Android Debug 证书、APK 签名与 zipalign。
成功后生成 `WifiShare-v<versionName>-release.apk`，并输出大小、SHA-256 与签名信息。

脚本会覆盖同版本的目标 APK；归档已有交付物后再构建，不能默默改变已发布版本的文件内容。
它只校验签名有效且不是 Debug 证书，不会自动证明使用了项目原来的正式密钥；
发布前仍需对比指纹和上一版 APK。

<a id="ui-previews"></a>
## UI 回归与预览

```bash
"$GRADLE_BIN" --no-daemon -Pkotlin.compiler.execution.strategy=in-process \
  testDebugUnitTest --tests io.iaw.lanshare.ActivityUiRegressionTest
```

预览输出在 `app/build/ui-previews/`；首页文档使用其中的 `home-light.png`、
`home-dark.png`、`settings-dark.png`。这些是实际 Activity 的离屏渲染，
使用空服务器列表，不包含真实配对凭据，也不是三星 One UI 真机截图。
更新文档图片前人工检查，再复制到仓库根目录的 `docs/images/`，与源码版本同步。
从本页约定的 `android/` 工作目录出发，目标路径应为 `../docs/images/`。

服务器栏崩溃的定向回归：

```bash
"$GRADLE_BIN" --no-daemon -Pkotlin.compiler.execution.strategy=in-process \
  testDebugUnitTest --tests io.iaw.lanshare.ServerRailScrollerTest
```

Robolectric 通过不等于真机通过。发版前还应检查覆盖升级、启动、快速切换服务器、
返回手势、系统 / App 明暗组合、大字体、中英文、小组件、通知和真实收发。
不能用静态截图替代触摸、后台运行、Keystore 或厂商桌面验证。

## 服务端验证

另开终端进入仓库的 `server/`：

```bash
cd /path/to/WifiShare/server
python3 -m unittest discover -s tests -v
python3 -m lss_server --help
```

使用[服务端指南](../server/README.md)准备 Python 与 OpenSSL。
测试应使用开发副本与临时数据，不要为了测试对生产 state 执行 `init`、`repair` 或 Token 轮换。
CLI 参数与默认值以 `lss_server/main.py` 为依据，文档修改时应同时核对 `--help`。

## 交付检查

1. 根据变更范围更新 `versionName`，递增 `versionCode`，维护倒序更新日志。
2. 记录实际执行的测试、结果和未验证项，不把历史结果写成本轮结果。
3. 记录 App 名称、版本、包名、APK 路径、字节数、SHA-256、签名证书指纹与验证命令。
4. 对照上一正式版本核验签名和升级路径；保留已交付 APK，不同版本不要共用可变文件名。
5. 提交前检查范围，排除 `server/state/`、配对链接、Token、私钥、签名材料、缓存和本机配置。
6. 只有真正发布并验证下载入口后，才更新首页的公开下载地址与“已发布”状态。

本地打包不等于 GitHub 发布；推送源码也不等于发布 APK。当前交付状态见[安装包说明](../README.md#download)。
历史构建的大小、哈希、测试数量及当时的验证限制保留在[历史归档](archive/development-history.md)，
不在首页重复维护。
