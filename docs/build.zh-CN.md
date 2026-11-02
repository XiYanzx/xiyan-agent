# 编译与可复现验证

应用工程固定 Gradle 8.11.1、Android Gradle Plugin 8.9.2、Android SDK 35 / Build Tools 35.0.0；Java 源码和字节码目标 17，云端用 Temurin JDK 21.0.13+9 构建。需要完整 JDK，只有 JRE 不足以运行 Gradle Java 编译任务。兼容关系见 [Android 官方 AGP 8.9 文档](https://developer.android.com/build/releases/agp-8-9-0-release-notes)。

```bash
git clone https://github.com/XiYanzx/xiyan-agent.git
cd xiyan-agent
git checkout feature/p0-runtime
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=/path/to/android-sdk
sdkmanager 'platforms;android-35' 'build-tools;35.0.0'
export XIYAN_SOURCE_COMMIT="$(git rev-parse HEAD)"
./gradlew --no-daemon :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
tools/package-p0.sh
```

`app/build/outputs/apk/debug/app-debug.apk` 是 Android 调试证书签名的可安装 P0。`assembleRelease` 产物没有生产签名，不作为下载 APK。发布脚本验证调试 APK 的签名与 ZIP 对齐，生成 `artifacts/` 下的 APK、签名报告、构建信息与 SHA256。脚本要求先有成功的核心 JUnit 结果。

Gradle wrapper 的发行包 SHA256 已固定在 `gradle/wrapper/gradle-wrapper.properties`。按[最新用户要求](REQUIREMENTS-ADDENDUM.zh-CN.md)，所有构建、lint、测试和 APK 打包仅在 Codex Cloud。仓库不包含 GitHub Actions 工作流；上传 Release 时直接使用对应 commit 的云端已验证产物，不二次编译。正式 P6 Release 需全部阶段与真机验收，P0 prerelease 不代替阶段验收；不自动批准或合并 PR。

## 当前 Codex Cloud 环境

工作区为 `/workspace/xiyan-agent`。工具链在 `/workspace/toolchains/`，缓存和 Android 用户目录在 `/workspace/.gradle`、`/workspace/.android`。只读 home 的 ADB 兼容设置若存在，是通过审批创建的 `.android` 目录链接；不修改 HOME、系统安全设置或网络策略。

云端必须保留环境注入的代理与 CA。Java 不自动采用 HTTP_PROXY 时，按环境网络快照为该次 Gradle 命令设置 `-Dhttps.proxyHost=proxy -Dhttps.proxyPort=8080 -Dhttp.proxyHost=proxy -Dhttp.proxyPort=8080`。下载的 JDK 使用环境已有的 CA trust store：`-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts`。这保留 TLS 校验；不要关闭校验或把云端代理配置写入公共 Gradle 用户配置。非该环境无需这些参数。

## 原生运行时实验

与应用 APK 构建分开，见 [runtime spike](runtime/P0-RUNTIME-SPIKE.zh-CN.md) 及 [脚本](../tools/runtime/README.zh-CN.md)。成功构建 Java APK 不能证明 app-server/helper 已能在 Android 运行；本 P0 APK 未打包 Codex/V8 或第三方 Termux 移植。NDK smoke 的 ELF 也不等于 Codex 运行时或手机启动成功。
