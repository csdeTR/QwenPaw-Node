# 发版流程

本文说明构建、签名、验收与发版收尾的完整流程，包含每一步的硬性约束与失败现象。
日常开发请见 [README](../README.md)，架构与状态流转见 [architecture.md](architecture.md)，
界面约定与性能测量口径见 [ui-guidelines.md](ui-guidelines.md)。

---

## 1、构建

### 1.1 常用命令

```bash
./gradlew :app:assembleFullDebug      # 产出 app/build/outputs/apk/full/debug/
./gradlew :app:assembleNosherpaDebug  # 产出 app/build/outputs/apk/nosherpa/debug/
./gradlew :app:assembleRelease        # 两个变体的 release 各四个架构
./gradlew :app:testFullDebugUnitTest     # 标准包的单测
./gradlew :app:testNosherpaDebugUnitTest # -no-sherpa 包的单测
./gradlew :app:installFullDebug          # 编译并安装标准包到已连接设备
```

任务名带变体前缀：`assemble<Flavor><BuildType>`、`test<Flavor><BuildType>UnitTest`、
`install<Flavor><BuildType>`。**没有 `testDebugUnitTest` 这个任务**——加了变体之后
单测只能按变体跑。`assembleDebug` 与 `assembleRelease` 仍是聚合目标，
一次会构建 8 个包，日常不要用。
日常只编译并装包时使用 `installFullDebug`，只跑单测时按变体各跑一次。

**不要直接执行 `./gradlew build`。** 该目标会连带执行 lint、release 构建与全部测试，
是整条命令链里最重的一条，在内存受限的机器上极易与设备测试或模拟器叠加导致 swap 打满。

### 1.2 构建前的环境检查

```bash
free -h
```

**可用内存低于 3 GB 时不要开始构建**，应先关闭模拟器与多余进程。

`assembleRelease` 包含 R8 混淆与优化，以及资源压缩，属于重活。
本项目历史上曾因同时运行「Gradle daemon + Kotlin daemon + 模拟器」把 swap 打满，
导致整个桌面环境失去响应。具体记录见 [devlog.md](devlog.md) 第 3 节。

### 1.3 技术栈

JDK 21、AGP 8.7.3、Kotlin 2.1.0、compileSdk 35、minSdk 26。

### 1.4 release 构建的特殊之处

release 开启了 R8（混淆与优化）与资源压缩。
因此**改动依赖之后必须重新验证一次 `assembleRelease`**，不能只验 debug：
某些反射、序列化或注解处理相关问题只在 R8 处理后暴露。

混淆规则在 `app/proguard-rules.pro`，覆盖 kotlinx.serialization、Room、
OkHttp 的可选依赖以及 Tink 的相关注解。

### 1.5 本机与 CI 的差异

三处差异都曾经造成过「本地通过、runner 失败」或「命令超时而非报错」。

**镜像仓库按环境切换。** `settings.gradle.kts` 在 `CI=true` 时跳过阿里云镜像，
只使用 `google()`、`mavenCentral()` 与 `gradlePluginPortal()`，
因为镜像在 GitHub Actions 的 runner 上不可达（见 5.3）。
Trae 的 shell 也带 `CI=true`，在其中构建走的是官方仓库，与用户自己的终端不同；
需要在本机复现 runner 的解析行为时，写 `CI=true ./gradlew <task>`。

**涉及时间与时区的单测必须显式固定时区。** runner 是 UTC，本机是 `Asia/Shanghai`，
断言里隐含默认时区就会本地通过、CI 失败。写法参照 `TimeLabelsTest`：

```kotlin
TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
// 断言中显式写 ZoneId.of("Asia/Shanghai")，不读运行环境的默认值
```

**在这台机器上执行 `git push` 要加两个环境变量。**

```bash
GIT_ASKPASS=/bin/false GIT_TERMINAL_PROMPT=0 git push
```

Trae 注入的 askpass 脚本在没有交互终端时会永久等待，表现为命令超时而非报错。

---

## 2、签名

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
产物无法安装或发布。识别方法很直接：产物名里带 `-unsigned` 就说明密钥没有被找到。
`build.gradle.kts` 只在 `keystore.properties` 里能读到 `storeFile` 时才创建 `release`
签名配置，因此「找不到密钥」与「`signingConfig` 为 null」是同一件事。

`splits.abi` 之后产物按架构拆分，签好名的文件名形如 `app-full-arm64-v8a-release.apk`，
未签名则是 `app-full-arm64-v8a-release-unsigned.apk`。

---

## 3、关于 baseline profile

release 包的 `assets/dexopt/` 目录里目前有 `baseline.prof` 与 `baseline.profm`，
它们来自 Compose 等依赖库自带的 profile，**不是本项目采样生成的**。

本项目没有引入 `androidx.profileinstaller` 依赖，也没有 baseline profile 生成任务。
如果后续需要自行采样，需要额外引入 `androidx.baselineprofile` 插件与对应模块。

这一条之所以单独说明，是因为很容易误以为 release 包已经做过 profile 优化，
从而在分析性能问题时得出错误结论。

---

## 4、验收

### 4.1 安装 release 包前必须先卸载

debug 与 release 使用不同签名，直接覆盖安装会失败：

```
INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package ... signatures do not match
```

正确顺序（`splits.abi` 之后产物按架构拆分，装设备对应那一份；变体按需选择）：

```bash
adb uninstall io.qwenpaw.node
adb install app/build/outputs/apk/full/release/app-full-arm64-v8a-release.apk              # 手机，标准包
adb install app/build/outputs/apk/nosherpa/release/app-nosherpa-x86_64-release.apk        # x86_64 模拟器，无 sherpa
```

若省掉卸载这一步，安装会直接失败，而报错文案不会明示「是因为签名不同」，
容易误判为 APK 损坏。装错架构则报 `INSTALL_FAILED_NO_MATCHING_ABIS`，
可用 `adb shell getprop ro.product.cpu.abi` 确认设备架构。

**两个语音变体共用同一个 applicationId**，因此互相覆盖安装即可升级，不需要先卸载；
但也就不能同机共存，要对比两种包时先卸载再装另一个。

**debug 与 release 用不同包名，可同机共存。** debug 的 applicationId 为
`io.qwenpaw.node.debug`，应用名为 `[debug]QP Node`（由
`app/src/debug/res/values/strings.xml` 覆盖 `app_name`）；release 仍为
`io.qwenpaw.node` / `QwenPaw Node`。装了 debug 也就不需要先卸载 release，
两者的登录状态与缓存互不影响。设备侧的 `pm`、`am` 命令与
`scripts/e2e_acceptance.sh` 默认按 debug 包名执行，脚本可用 `ADB_PKG` 覆盖。

### 4.2 流畅度必须用 release 包验证

debug 变体 `isMinifyEnabled = false`：没有 R8 内联与优化，没有 AOT，
Compose 内部还会走 debug 断言路径。实测对话页滚动的主线程帧耗时，
**release 约为 debug 的 43%**，长帧从 43ms 压到 12ms。

完整数据与测量方法见 [ui-guidelines.md](ui-guidelines.md) 第 5 节。

> `scripts/e2e_acceptance.sh` 安装的是 **debug** 包。
> **脚本通过不等于用户手上流畅。** 流畅度这类主观指标必须另外用 release 包实测。

### 4.3 端到端验收脚本

```bash
./scripts/e2e_acceptance.sh               # 全部场景 A1–A7、B1–B6
./scripts/e2e_acceptance.sh --only A4     # 只跑单个场景，推荐
```

运行前提：设备已连接、LXC 测试容器已启动（见 [.agents/test-env.md](../.agents/test-env.md) 第 1 节）、
且已执行过 `./gradlew :app:assembleFullDebug`（验 `-no-sherpa` 包时改成
`assembleNosherpaDebug`，并用 `ADB_FLAVOR=nosherpa` 让脚本取对产物）。

容器内的 `qwenpaw` 只在交互式 shell 的 PATH 中（见 [test-env.md](../.agents/test-env.md) 1.1），
`lxc exec` 之外的命令一律写全路径 `/home/csdetr/.qwenpaw/bin/qwenpaw`。

| 场景 | 内容 | 额外要求与常见失败原因 |
| --- | --- | --- |
| A1 | 审批：触发后点允许 | 需要服务端 `approval_level=STRICT`。若为 `AUTO`，只有 `guarded_tools` 名单内的工具才触发审批，脚本未必能命中；若为 `OFF`，脚本直接跳过。**`ASK` 不是合法档位**，写成它会被 `ToolExecutionLevel.from_config` 静默回退为 `AUTO` |
| A2 | 附件：上传相册图片并确认服务端接收 | 设备相册为空时脚本找不到可上传的图片 |
| A3 | 重连：长任务中杀掉 App，重开后接回输出 | 若任务在杀进程前已经结束，就测不到重连路径 |
| A4 | 停止：生成中停止，服务端回 idle | 生成过快时可能来不及点停止 |
| A5 | 多实例：第二个实例的会话列表与第一个隔离 | 需要第二个实例运行在 `:8099`，否则连接阶段就失败 |
| A6 | 断网 / 409 | 在 redroid 容器内加黑洞路由断网（见 [test-env.md](../.agents/test-env.md) 2.4）；本机容器无 netfilter 表，不能改用 iptables |
| A7 | 工作区：设置入口可达、三个根可切换 | 记忆根为空时只能看到空列表，「列出 Markdown」一项会跳过 |
| B1 | 检查点页：自动开关与手动快照区可见 | 服务端缺 `git` 时 `/status` 返回 400，页面只读，「手动快照」不出现；容器内缺 git 用 `apt-get install -y git` 后重启服务 |
| B2 | 只读对话页：底部是「创建检查点」且没有输入框 | 依赖检查点页有可点的会话；服务端还没有会话时脚本跳过 |
| B3 | 创建检查点：命名后服务端出现该检查点 | 判据读 `/api/workspace/checkpoints/graph` 而不是看屏幕，因为时间线按会话分组，新节点可能在首屏之外 |
| B4 | 回滚预览：记忆与文件两个范围可见 | 只打开预览不确认；没有检查点时脚本跳过 |
| B5 | 自动检查点开关：切换后服务端状态跟随并还原 | 开关本体不可点，脚本按「自动创建检查点」这一行最右侧的可点控件定位 |
| B6 | 备份页：新建与导入入口都在 | 只验入口，不创建备份，避免在测试容器里留归档 |

**B3 会重复创建同名检查点。** 同一工作区可以存在多个名为 `acceptance` 的检查点，
判据只要求存在至少一个，不区分是本次还是上一次留下的。
测完用 `DELETE /api/workspace/checkpoints` 清理（`X-Agent-Id` 指定智能体）。

**一次只跑一个场景**（例如 `--only A4`），跑完检查 `free -h`。
不要整轮跑完再一起收尾：模拟器与构建同时运行极易拖垮桌面环境。

A6 的实现细节需要注意：脚本从 `HOST` 中取出服务端 IP，加一条
`ip route add blackhole <ip>/32`，封的是地址而不是端口；`run_root` 只是 `adb shell`，
redroid 是 userdebug，`adb shell` 默认 `uid=0`，不需要 `adb root`。
封闭的目标是服务端 IP，**不是 adb 使用的 5555**；误封 5555 会直接断开 adb 连接。

---

## 5、发版

### 5.1 版本号

版本号在 `app/build.gradle.kts` 的 `defaultConfig` 中：

```kotlin
versionCode = 2          // 每次发版必须 +1，Android 依靠它判断升级
versionName = "0.1.1"    // 展示用，应与 git tag 保持一致
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
| `v0.1.1` | 见 `git rev-list -n1 v0.1.1` | 图标集换 lucide、LaTeX 排版、空对话首页、设置页两级（附注 tag） |
| `v0.1.2` | 见 `git rev-list -n1 v0.1.2` | 正文裸上下标的判定收口（附注 tag） |
| `v0.1.4` | 见 `git rev-list -n1 v0.1.4` | 分页骨架、频道筛选、斜杠补全、上下标字号、收件箱、工具/环境变量/后台策略（附注 tag）。0.1.3 未单独发版，内容并入本版 |
| `v0.2.0` | 见 `git rev-list -n1 v0.2.0` | 定时任务管理与工具调用控制、双时区对照、会话选择器补名称与搜索、工具卡片 7.5 秒自动展开（附注 tag） |
| `v0.2.1` | 见 `git rev-list -n1 v0.2.1` | 401 分类、反向代理 HTTP Basic、安全提示、流式首屏与重连合并、配套插件探测、分组重排、已归档批量恢复、登出与吊销凭据；另有新建对话切不动等七处修复（附注 tag） |
| `v0.2.1-b2` | 见 `git rev-list -n1 v0.2.1-b2` | 安全提示拆成「未登录」与「未加密」两个弹窗，补「文档」入口；`versionCode` 7（附注 tag） |
| `v0.2.2` | 见 `git rev-list -n1 v0.2.2` | 配套插件检索与事件流、思考强度滑块、聊天字号预览、收件箱按来源与智能体筛选、历史解码修复；`versionCode` 8（附注 tag） |
| `v0.3.0` | 见 `git rev-list -n1 v0.3.0` | 工作区文件浏览与下载、输入区重做（单一圆角框、思考强度脑图、全屏输入、魔法命令）、受信任的连接、收件箱筛选改版、两侧消息渲染开关；`versionCode` 9（附注 tag） |
| `v0.3.1` | 见 `git rev-list -n1 v0.3.1` | 工作区文本编辑与保存（`etag` 乐观锁、409 冲突的重新加载与强制覆盖、未保存确认）、多文件上传（逐文件进度与取消、上传上限、同名冲突覆盖或跳过）；`versionCode` 10（附注 tag） |
| `v0.3.2` | 见 `git rev-list -n1 v0.3.2` | 备份（创建与 SSE 进度、列表与详情、导出与导入、重名覆盖、签名信任、删除）、整包与选择性恢复；检查点（自动开关、时间线、手动快照、预览与回滚、GC 策略与重置），手动快照改为先进入只读对话页；验收补 B1–B6；`versionCode` 11（附注 tag） |
| `v0.4.0` | 见 `git rev-list -n1 v0.4.0` | 语音拆双变体（标准包本地识别与服务端转写二选一，`-no-sherpa` 只保留服务端转写）、服务端转写可用性探测、8 个安装包；`versionCode` 12（附注 tag） |
| `v0.4.2` | 见 `git rev-list -n1 v0.4.2` | 用量统计扩展（时间范围、当前智能体汇总、趋势折线、按模型明细）、模型与提供商页、设置页返回后保留滚动位置；`versionCode` 14（附注 tag）。0.4.1 未单独发版，内容并入本版 |

使用附注 tag 而非轻量 tag，是为了让 `git show v0.1.0` 能直接看到当时的版本说明，
不必去翻提交历史。

### 5.3 自动发布

推送 `v*` tag 即触发 `.github/workflows/release.yml`，流程为：运行两个变体的
JVM 单测 → `assembleRelease` → `apksigner verify` 校验签名 → 重命名产物 →
创建 Release 并上传八个包（2 变体 × 4 架构，少一个就失败）。
需要补发时用 `workflow_dispatch`，输入已存在的 tag，
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

Release 正文以 `CHANGELOG.md` 的版本小节开头，workflow 抽取第一个版本小节，
并要求它与 `$TAG` 一致，否则中止，后面接安装包表格与自动生成的 Full Changelog。
正文不再另加「本版变化」一类标题：CHANGELOG 小节本身就是正文，
多一层标题只是把同一件事说两遍。
两处各写一份历史上已经对不上过，因此正文以 CHANGELOG 为唯一来源，
安装包表格与签名说明仍写在 workflow 里。
抽取失败不静默跳过：正文缺一段，比发出去的说明与代码不一致更难被发现。

`append_body` 必须保持 `false`。它是给「分几步往同一个 Release 追加内容」用的，
重跑时会叠在上一版正文后面，安装包表格出现两次；实测过一次才发现，
workflow 里已写明原因。

workflow 运行时带 `CI=true`，`settings.gradle.kts` 据此跳过阿里云镜像，
只使用 `google()`、`mavenCentral()` 与 `gradlePluginPortal()`。
镜像在本机可达、在 runner 上不可达，这一分支不能删。

---

## 6、发版后收尾

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

## 7、发布形态的限制

### 7.1 两个变体，各按 ABI 拆分

0.4.0 起每次发版产出 **8 个 APK**：`full` 与 `nosherpa` 两个变体，各覆盖四个架构。
`splits.abi` 使用 `isUniversalApk = false`，每台设备只取自己那一份。

| 变体 | 语音能力 | 产物目录 |
| --- | --- | --- |
| `full` | 本地识别（默认）与服务端转写二选一 | `app/build/outputs/apk/full/release/` |
| `nosherpa` | 只能服务端转写 | `app/build/outputs/apk/nosherpa/release/` |

**变体差异靠 Gradle 源码集实现，不是同一份代码里的运行时开关**：
`sherpa-onnx` 的 AAR 与 `commons-compress` 都声明成 `fullImplementation`，
`SherpaAsr` / `SherpaEngine` 只放在 `app/src/full/`，`nosherpa` 的同名
`LocalAsrProvider` 返回 `LocalAsr.Disabled`。因此 `nosherpa` 编译期就不含这些类，
四个 ABI 的包都不带 `libsherpa-onnx-jni.so`。理由与接缝细节见
[protocol.md](protocol.md) 第 14 节。

文件名规则（workflow 统一改名）：

```
QwenPaw-Node-<tag>-<abi>.apk              # full
QwenPaw-Node-<tag>-no-sherpa-<abi>.apk    # nosherpa
```

`-no-sherpa` 只进文件名与 `versionName`，**`applicationId` 两个变体相同**，
可以互相覆盖升级，登录态、字体、设置都跟着走。代价是两版不能同机共存，
需要对比时先卸载再装另一个。

拆包的理由在 `full` 上仍然成立：sherpa-onnx 的 `.so` 每个 ABI 14–30MB，
四个合进一个包光 native 就 90MB 出头。`nosherpa` 的四个包都只有 2.4MB 左右，
拆包对它收益很小，但两条产物线共用同一套命名与校验，不单独区别对待。

**`splits.abi` 与 `ndk.abiFilters` 二选一，同时存在时后者被忽略**，
因此不再保留 `abiFilters` 那一行。本地想只出一个架构时，临时把 `include()` 收窄即可。

| ABI | 实测状态 | 说明 |
| --- | --- | --- |
| `arm64-v8a` | **已验证** | 覆盖 2019 年后的主流手机；此前多轮真机验证过安装、启动、连接与对话 |
| `x86_64` | **已验证** | 模拟器 `qp35` 与 redroid 容器上安装 release 包、走完引导第 1 步连接验证 |
| `armeabi-v7a` | **未验证** | 本机无 32 位 ARM 设备；仅确认能编译产出 |
| `x86` | **未验证** | API 30 起官方不再提供 32 位 x86 系统镜像，无设备可测 |

**未验证不等于不能用，但也不能写成「支持」。** 对外只陈述「额外构建、未实测」，
不承诺可用性。这一口径要同步到 README 的下载说明，避免用户从 Release 页面
看到八个包就以为都经过验证。

### 7.2 体积现状

实测命令：`find app/build/outputs/apk -path "*/release/*.apk" -printf "%s %p\n"`。

0.4.2 实测（`full` / `nosherpa`，取自 `v0.4.2` 的 Release 产物）：

| 架构 | `full` | `nosherpa` | `full` 包内 native |
| --- | --- | --- | --- |
| `arm64-v8a` | 25,404,422（约 25.4MB） | 2,848,411（约 2.85MB） | `libsherpa-onnx-jni.so` 22.4MB |
| `armeabi-v7a` | 18,175,242（约 18.2MB） | 2,845,569 | 同上量级 |
| `x86_64` | 28,350,712（约 28.4MB） | 2,849,072 | 同上 |
| `x86` | 39,355,921（约 39.4MB） | 2,847,593 | 另带 `libonnxruntime.so` |

`nosherpa` 的四个包内只剩 `libandroidx.graphics.path.so`（10KB）。
`full` 的 arm64 里 sherpa 的 `.so` 仍占近九成；
`x86` 一枝独秀是因为它额外带 `libonnxruntime.so`。

**0.4.1 引入图表库后，每个包相对 0.4.0 增加约 392KB**（`nosherpa` arm64 由 2,447,277 增至 2,848,411），
来源是新增的图表库 Vico（`compose` + `compose-m3` 2.0.0）及其传递依赖：
Vico `core` 的 AAR 约 593KB，另外它声明了 `appcompat` 与 `fragment`，
两者在 R8 后只剩被实际引用的部分。选 2.0.0 而不是更新版本，是因为它的
`compose-bom` 与本项目一致（2024.12.01），2.1 之后会把 Compose 一起抬高。

**新增依赖必做两件事**（AGENTS.md 第 4 节）：补 `proguard-rules.pro` 的 keep 规则，
并复核两个变体 × 四个 ABI 的体积。本次 Vico 唯一的反射是 `StaticLayout.setLineCount`，
只在 API < 23 的分支走到，本项目 minSdk 26，因此**没有新增 keep 规则**。

### 7.3 字体与模型都不随包分发

MiSans 字体不打进 APK，由用户在应用内按需下载，因此首次安装的包体较小，
但 MiSans 字体需要先完成下载才能使用。

语音识别模型同样不打进包：`full` 的「设置 → 语音 → 语音识别模型」在用户点击后
从 sherpa-onnx 官方 release 下载（约 150MB），`nosherpa` 没有这一行，也不需要下载。