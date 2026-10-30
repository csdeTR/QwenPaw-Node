# 发版流程

本文说明构建、签名、验收与发版收尾的完整流程，包含每一步的硬性约束与失败现象。
日常开发请见 [README](../README.md)，架构与状态流转见 [architecture.md](architecture.md)，
界面约定与性能测量口径见 [ui-guidelines.md](ui-guidelines.md)。

---

## 一、构建

### 1.1 常用命令

```bash
./gradlew :app:assembleDebug        # 产出 app/build/outputs/apk/debug/
./gradlew :app:assembleRelease      # 产出 app/build/outputs/apk/release/
./gradlew :app:testDebugUnitTest    # 运行 JVM 单测
./gradlew :app:installDebug         # 编译并安装到已连接设备
```

日常只编译并装包时使用 `installDebug`，只跑单测时使用 `testDebugUnitTest`。

**不要直接执行 `./gradlew build`。** 该目标会连带执行 lint、release 构建与全部测试，
是整条命令链里最重的一条，在内存受限的机器上极易与设备测试或模拟器叠加导致 swap 打满。

### 1.2 构建前的环境检查

```bash
free -h
```

**可用内存低于 3 GB 时不要开始构建**，应先关闭模拟器与多余进程。

`assembleRelease` 包含 R8 混淆与优化，以及资源压缩，属于重活。
本项目历史上曾因同时运行「Gradle daemon + Kotlin daemon + 模拟器」把 swap 打满，
导致整个桌面环境失去响应。具体记录见 [devlog.md](devlog.md) 第三节。

### 1.3 技术栈

JDK 21、AGP 8.7.3、Kotlin 2.1.0、compileSdk 35、minSdk 26。

### 1.4 release 构建的特殊之处

release 开启了 R8（混淆与优化）与资源压缩。
因此**改动依赖之后必须重新验证一次 `assembleRelease`**，不能只验 debug：
某些反射、序列化或注解处理相关问题只在 R8 处理后暴露。

混淆规则在 `app/proguard-rules.pro`，覆盖 kotlinx.serialization、Room、
OkHttp 的可选依赖以及 Tink 的相关注解。

---

## 二、签名

### 2.1 密钥位置

密钥存放在**仓库之外**：`~/.qwenpaw-node-keys/`

```
release.jks            # 密钥库
keystore.properties    # storeFile / storePassword / keyAlias / keyPassword
```

也可以通过环境变量 `QWENPAW_KEYSTORE_PROPERTIES` 指向其他位置的属性文件。

### 2.2 密钥不得入库

这两个文件**绝不能提交进仓库**。`.gitignore` 已排除 `*.jks`、`*.keystore`
与 `keystore.properties`。

### 2.3 密钥缺失时的行为

密钥文件缺失时，构建**不会失败**，但 release 会退化为**未签名**状态，
产物无法安装或发布。识别方法很直接：出现 `app-release-unsigned.apk` 就说明密钥没有被找到。
正常的产物名是 `app-release.apk`。

---

## 三、关于 baseline profile

release 包的 `assets/dexopt/` 目录里目前有 `baseline.prof` 与 `baseline.profm`，
它们来自 Compose 等依赖库自带的 profile，**不是本项目采样生成的**。

本项目没有引入 `androidx.profileinstaller` 依赖，也没有 baseline profile 生成任务。
如果后续需要自行采样，需要额外引入 `androidx.baselineprofile` 插件与对应模块。

这一条之所以单独说明，是因为很容易误以为 release 包已经做过 profile 优化，
从而在分析性能问题时得出错误结论。

---

## 四、验收

### 4.1 安装 release 包前必须先卸载

debug 与 release 使用不同签名，直接覆盖安装会失败：

```
INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package ... signatures do not match
```

正确顺序：

```bash
adb uninstall io.qwenpaw.node
adb install app/build/outputs/apk/release/app-release.apk
```

若省掉卸载这一步，安装会直接失败，而报错文案不会明示「是因为签名不同」，
容易误判为 APK 损坏。

### 4.2 流畅度必须用 release 包验证

debug 变体 `isMinifyEnabled = false`：没有 R8 内联与优化，没有 AOT，
Compose 内部还会走 debug 断言路径。实测对话页滚动的主线程帧耗时，
**release 约为 debug 的 43%**，长帧从 43ms 压到 12ms。

完整数据与测量方法见 [ui-guidelines.md](ui-guidelines.md) 第五节。

> `scripts/e2e_acceptance.sh` 中硬编码安装的是 **debug** 包
> （`APK=.../apk/debug/app-debug.apk`）。
> **脚本通过不等于用户手上流畅。** 流畅度这类主观指标必须另外用 release 包实测。

### 4.3 端到端验收脚本

```bash
./scripts/e2e_acceptance.sh               # 全部场景 A1–A6
./scripts/e2e_acceptance.sh --only A4     # 只跑单个场景，推荐
ADB_SERIAL=192.168.1.20:5555 ./scripts/e2e_acceptance.sh --only A3
```

运行前提：设备已连接、宿主机上运行着 `qwenpaw app --host 0.0.0.0`、
且已执行过 `./gradlew :app:assembleDebug`。

| 场景 | 内容 | 额外要求与常见失败原因 |
| --- | --- | --- |
| A1 | 审批：触发后点允许 | 需要服务端 `approval_level=ASK`。若仍是 `AUTO`，工具会直接执行、不产生审批项，脚本会一直等待而超时 |
| A2 | 附件：上传相册图片并确认服务端接收 | 设备相册为空时脚本找不到可上传的图片 |
| A3 | 重连：长任务中杀掉 App，重开后接回输出 | 若任务在杀进程前已经结束，就测不到重连路径 |
| A4 | 停止：生成中停止，服务端回 idle | 生成过快时可能来不及点停止 |
| A5 | 多实例：第二个实例的会话列表与第一个隔离 | 需要第二个实例运行在 `:8099`，否则连接阶段就失败 |
| A6 | 断网 / 409 | 需要 `adb root` 配置 iptables。模拟器可 `adb root`，因此该场景可完整执行 |

**一次只跑一个场景**（例如 `--only A4`），跑完检查 `free -h`。
不要整轮跑完再一起收尾：模拟器与构建同时运行极易拖垮桌面环境。

A6 的实现细节需要注意：脚本会自动探测三种提权方式（已 adb root、`adb root` 成功、su 免密），
并依据 `HOST` 中的端口来封闭网络，不再写死 8088。
封闭的目标端口是服务端端口，**不是 adb 使用的 5555**；误封 5555 会直接断开 adb 连接。

---

## 五、发版

### 5.1 版本号

版本号在 `app/build.gradle.kts` 的 `defaultConfig` 中：

```kotlin
versionCode = 1          // 每次发版必须 +1，Android 依靠它判断升级
versionName = "0.1.0"    // 展示用，应与 git tag 保持一致
```

`versionCode` 遗漏递增会导致新包无法覆盖安装；`versionName` 与 tag 不一致，
则用户看到的版本号与 Release 标题对不上。

### 5.2 打 tag

tag 约定为 `v<versionName>`，使用**附注 tag**（即带发版说明的 tag）：

```bash
git tag -a v0.1.0 -m "QwenPaw Node v0.1.0 — 首个最小可用版本"
```

已有的 tag 与 commit 对应关系：

| tag | 指向 | 说明 |
| --- | --- | --- |
| `v0.1.0` | `18f3df1` | 首个最小可用版本（附注 tag） |
| `v0.1.0-b2` | `1f5764a` | 四架构包，由 workflow 自动构建并上传（附注 tag） |

使用附注 tag 而非轻量 tag，是为了让 `git show v0.1.0` 能直接看到当时的版本说明，
不必去翻提交历史。

### 5.3 自动发布

推送 `v*` tag 即触发 `.github/workflows/release.yml`，流程为：运行 JVM 单测 →
`assembleRelease` → `apksigner verify` 校验签名 → 重命名产物 →
创建 Release 并上传四个包。需要补发时用 `workflow_dispatch`，输入已存在的 tag，
版本名取自该输入，而不是触发时的分支名。

签名密钥不进仓库，由四个 Repository secrets 在构建时还原：

| secret | 内容 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | `base64 -w0 ~/.qwenpaw-node-keys/release.jks` 的单行输出 |
| `ANDROID_KEYSTORE_PASSWORD` | `keystore.properties` 的 `storePassword` |
| `ANDROID_KEY_ALIAS` | 同一文件的 `keyAlias`（当前为 `qwenpaw-node`） |
| `ANDROID_KEY_PASSWORD` | 同一文件的 `keyPassword` |

secrets 缺失时 workflow 在「解码签名密钥」一步报错退出，不会产出未签名的包；
`apksigner verify` 不通过同样中止发布。产物命名为 `QwenPaw-Node-<tag>-<abi>.apk`。

workflow 运行时带 `CI=true`，`settings.gradle.kts` 据此跳过阿里云镜像，
只使用 `google()`、`mavenCentral()` 与 `gradlePluginPortal()`。
镜像在本机可达、在 runner 上不可达，这一分支不能删。

---

## 六、发版后收尾

按 [AGENTS.md](../AGENTS.md) 的资源约束，构建与测试结束后**不要保留常驻进程**：

```bash
./gradlew --stop              # 停止 Gradle daemon
pkill -f KotlinCompileDaemon  # 停止 Kotlin 编译 daemon
adb emu kill                  # 关闭模拟器（使用模拟器时）
```

随后执行 `free -h`，确认内存回到正常水位。

同时更新 [CHANGELOG.md](../CHANGELOG.md)，写清本版对用户可见的变化，
尤其是「修了卡顿」这类感知明显的项目。

---

## 七、发布形态的限制

### 7.1 按 ABI 拆分，不打单一全量包

`splits.abi` 覆盖四个架构，`isUniversalApk = false`。不打「全架构单包」的原因是
sherpa-onnx 的 `.so` 每个 ABI 14–30MB，四个合进一个包光 native 就 93MB，
用户为聊天下载 93MB 不合理。拆开后每台设备只取自己那份。

**`splits.abi` 与 `ndk.abiFilters` 二选一，同时存在时后者被忽略**，
因此不再保留 `abiFilters` 那一行。本地想只出一个架构时，临时把 `include()` 收窄即可。

| ABI | 实测状态 | 说明 |
| --- | --- | --- |
| `arm64-v8a` | **已验证** | 覆盖 2019 年后的主流手机；此前多轮真机验证过安装、启动、连接与对话 |
| `x86_64` | **已验证** | 模拟器 `qp35` 上安装 release 包、走完引导第 1 步连接验证 |
| `armeabi-v7a` | **未验证** | 本机无 32 位 ARM 设备；仅确认能编译产出 |
| `x86` | **未验证** | API 30 起官方不再提供 32 位 x86 系统镜像，无设备可测 |

**未验证不等于不能用，但也不能写成「支持」。** 对外只陈述「额外构建、未实测」，
不承诺可用性。这一口径要同步到 README 的下载说明，避免用户从 Release 页面
看到四个包就以为都经过验证。

### 7.2 体积现状

实测命令：`find app/build/outputs/apk/release -name "*.apk" -printf "%s %f\n"`。

| 架构 | release APK | native 构成 |
| --- | --- | --- |
| `arm64-v8a` | 24,497,163 字节（约 23.4MB） | `libsherpa-onnx-jni.so` 21.4MB |
| `armeabi-v7a` | 17,267,983 字节（约 16.5MB） | 14.5MB |
| `x86_64` | 27,443,453 字节（约 26.2MB） | 24.2MB |
| `x86` | 38,448,662 字节（约 36.7MB） | `libsherpa-onnx-jni.so` 5.1MB + `libonnxruntime.so` 29.6MB |

`x86` 明显大于其它三个：只有它在 AAR 里额外带了一份独立的 `libonnxruntime.so`。

release 的体积几乎全部是 sherpa-onnx 的 native 库，这一点无法继续压缩——
语音识别引擎本身就有这么大。四个包的 dex 相同，R8 只跑一次。

### 7.3 字体与模型不随包分发

MiSans 字体与语音识别模型都不打进 APK，由用户在应用内按需下载。
因此首次安装的包体较小，但语音输入与 MiSans 字体需要先完成下载才能使用。