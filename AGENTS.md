# Agent 约定

本文只写每轮都要生效的约定与判据。操作步骤、环境细节与写作规范放在归属文档里，
正文留一行指针。第 1 节关于系统资源的条目属于**硬约束**，不遵守会直接影响同机的桌面环境。

## 0、协作方式

这一节约束工作方式而非工程细节，因此放在最前。

1. **用户给出的路线、方案、实现不等于最优解。** 发现更合适的做法时，
   应先说明它好在哪、代价是什么、与用户所给方案的差异，再由用户选择，
   而不是默认照办。用户的方案往往基于本文档看不到的背景，因此提出异议
   必须带依据（实测数据、代码位置、服务端字段名），不能只表达偏好。
2. **需要确认的地方先提问。** 需求含糊、涉及破坏性操作、要在多种合理实现之间取舍、
   或改动会越出本轮范围时，先问再做。判断标准是「做完才发现方向不对」的返工成本
   是否高于一次提问的成本；是则先问。
3. 提问要收敛：一次给出二到四个可选项，每项写明后果，推荐项放第一位。

## 1、系统资源：硬约束

本机内存 15 GB、swap 8 GB，同时运行着 GNOME 桌面、Trae、QwenPaw 服务端与模拟器壁纸渲染。
曾经发生一次 **GNOME 整个卡死**，原因是同时运行了「Gradle daemon + Kotlin daemon + 模拟器」，
swap 被完全占满，桌面因 swap thrash 而失去响应。

以下八条必须遵守。

1. **不要连续执行构建与设备测试。**
   全量构建、`connectedAndroidTest`、`assembleRelease`（含 R8）都属于重活。
   应逐个执行，并在两次之间留出间隔，避免内存占用叠加。

2. **动手前先看内存。** 执行 `free -h`，「可用内存」与「swap 已用」两项都要看。
   可用内存低于 3 GB，或 swap 已用超过总量的一半（4 GB）时，
   应先关闭测试设备、测试容器与多余进程，再开始构建。
   只看 available 不够：swap 被占到一半以上说明系统已经在换页，
   此时再叠加构建任务，就是那次卡死的直接前兆。

3. **用完即关，不要保留常驻进程。** 测试设备与测试服务端都按需启动，用完立即关闭，
   不因为「下次还要用」而挂在后台。
   - Gradle daemon：`./gradlew --stop`
   - Kotlin daemon：`pkill -f KotlinCompileDaemon`
   - redroid 容器：`docker compose -f scripts/redroid/docker-compose.yml down`
   - LXC 测试容器：`lxc stop qp-test qp-test-prerelease`
     （只停容器，LXD 服务与 `lxdbr0` 保留，下次直接 `lxc start`）
   - AVD：`~/Android/Sdk/platform-tools/adb -s emulator-5554 emu kill`

   忘记收尾会导致下一次构建时内存基线偏高，进而挤占桌面环境。

4. **redroid 容器的资源上限固定在 compose 里，不要调高。**
   `mem_limit` / `memswap_limit` 3 GB、`cpus` 2、`pids_limit` 4096。
   实测空闲占用 1.0–1.3 GiB；不设上限时容器曾把 swap 吃满，宿主机随即卡死。
   参数与依据见 [.agents/test-env.md](.agents/test-env.md) 第 2 节。

5. **优先增量构建。**
   只编译并装包时用 `./gradlew installFullDebug`；只跑单测时用
   `./gradlew :app:testFullDebugUnitTest`（`-no-sherpa` 包用 `testNosherpaDebugUnitTest`）。
   不要顺手敲 `./gradlew build`，该目标会连带执行 lint、release 构建与全部测试；
   `assembleDebug` / `assembleRelease` 也会一次构建 8 个包，同样避免。

6. **单测优先于设备测试。**
   设备端测试启动成本高，失败信息也差。能覆盖的逻辑一律写成 JVM 单测。

7. **不要同时启动多个 Gradle 实例。**
   命令应排队执行，不要并行开多个终端同时运行 `./gradlew`。

8. **设备验收一次只跑一个场景。**
   `scripts/e2e_acceptance.sh` 使用 `--only A4` 形式的单场景参数，跑完检查 `free -h`。
   不要整轮跑完再统一收尾，因为测试容器与构建同时运行极易拖垮桌面环境。

## 2、服务端：只用测试容器

**测试一律使用 LXC 容器，不使用本机的 `:8088` 实例。** 本机实例承载用户的真实会话与记忆，
在上面做写操作会污染真实数据，历史上有过误归档真实会话的教训。

| 容器 | IP | 版本 | 用途 |
| --- | --- | --- | --- |
| `qp-test` | `10.10.0.211:8088` | `2.2.2.post1` | 稳定版基线，与用户当前使用的版本一致 |
| `qp-test-prerelease` | `10.10.0.220:8088` | `2.3.0b2` | 预发布版，用于提前发现破坏性变更 |

- 启动、停止、探活、`lxdbr0` 排查、审批档位与还原核对：见
  [.agents/test-env.md](.agents/test-env.md) 第 1 节。
- 两个版本的端点差异、404 与 405 的判据、无数据实例上的 `null` 形状：见
  [docs/protocol.md](docs/protocol.md) 第 17 节。
  客户端实际使用的路径在两版完全一致（2026-11-01 比对 33 条；0.3.2 又逐文件比对了
  备份与检查点的路由与模型），因此**不做运行时版本分支**。

## 3、测试设备

**真机测试已停用，不要再尝试连接真机。** 首选 redroid 容器，redroid 不可用时回退 AVD `qp35`。
两者的显示参数一致：720×1600、280dpi。

- 启动步骤、A6 断网、到服务端的网络路径、验收脚本与截图采样：见
  [.agents/test-env.md](.agents/test-env.md) 第 2–4 节。
- 三条硬性约束留在本文第 1 节：不改 compose 的资源上限、不换回 Android 12 镜像、用完即关。

## 4、构建与签名

- 技术栈、常用命令与 release 构建的特殊之处见 [docs/release.md](docs/release.md) 1.1–1.4；
  签名密钥的位置与「构建成功不等于包可用」的判据见第 2 节。
- **新增或升级依赖必做两件事。** 一是补 `app/proguard-rules.pro` 的 keep 规则；
  二是复核两个变体 × 四个 ABI 的包体积（`sherpa-onnx` 的 `.so` 每架构 14–30 MB，
  且只进 `full`，不能只看 arm64-v8a 或只看标准包）。见 release.md 1.4 与 7。
- **涉及时间与时区的单测必须显式固定 `ZoneId`。** 本机是 `Asia/Shanghai`、runner 是 UTC，
  本地通过而 CI 失败的典型原因就是断言里隐含了默认时区，写法参照 `TimeLabelsTest`。
- **镜像仓库按环境切换。** `CI=true` 时只用 `google()` / `mavenCentral()` / `gradlePluginPortal()`；
  Trae 的 shell 也带 `CI=true`，在本机复现 runner 行为用 `CI=true ./gradlew <task>`。
  见 release.md 1.5 与 5.3。
- **在这台机器上执行 `git push` 要加 `GIT_ASKPASS=/bin/false GIT_TERMINAL_PROMPT=0`。**
  Trae 注入的 askpass 脚本在没有交互终端时会永久等待，表现为命令超时而非报错。
- **debug 与 release 用不同包名，可同机共存。** debug 为 `io.qwenpaw.node.debug` / `[debug]QP Node`，
  release 为 `io.qwenpaw.node` / `QwenPaw Node`。设备侧 `pm`、`am` 与验收脚本默认按 debug 执行，
  脚本可用 `ADB_PKG` 覆盖。
- **语音有两个构建变体。** `full` 带端上识别引擎、可二选一来源；`nosherpa` 不带，
  只能服务端转写。变体名只进产物文件名与 `versionName`，`applicationId` 相同、可互相覆盖，
  不能同机共存。见 release.md 7.1；改动 `voice/` 时两个变体都要编译与跑单测。

## 5、文档

- 每轮先在 `draft/` 下建一个 `YYYY.MM.DD-HH·MM·SS.md` 草稿，时间取用户发起这一轮的时刻；
  过程性内容只写草稿，该目录已被 `.gitignore` 排除。
- **改动完成、结论确定之前，不得修改 `docs/`、`README.md`、`CHANGELOG.md`、`待办清单.md`。**
  一边做一边改，被压缩掉的中间状态就会以确定语气留在文档里，
  读者无法分辨哪些是已核实的结论、哪些是中途被推翻的设想。
- 顺序固定：建草稿 → 编码与验证 → 把结论提炼进正式文档与 `docs/devlog.md` → 删除草稿。
  只写草稿而不更新正式文档，等于这一轮没有沉淀。
- 语体分档、开发日志写法、各文档分工、界面文案：见 [.agents/writing.md](.agents/writing.md)。
- **界面文案一律用书面语，副标题与灰字说明同样算界面文案。** 「公式也不渲染」这类口语
  已经反复出现在开关说明里，属于必须回查的一类缺陷；改写方式见
  [.agents/writing.md](.agents/writing.md) 第 4 节。

## 6、提交与门禁

### 6.1 提交信息

仓库只有 `master` 一个长期分支，提交信息统一使用「前缀: 中文描述」的形式。

| 前缀 | 适用 |
| --- | --- |
| `feat` | 新增功能或用户可感知的行为 |
| `fix` | 修复缺陷 |
| `test` | 只动测试代码 |
| `docs` | 只动文档，含 `docs/`、`README.md`、`AGENTS.md` |
| `build` | 构建配置、依赖、版本号、Gradle 与 ProGuard 规则 |
| `ci` | `.github/workflows/` 与 CI 相关脚本 |
| `chore` | 许可、忽略规则、素材整理等杂项 |

描述写**做了什么**，不写「更新代码」这类无信息量的说法；同一轮的多个文件放在一个提交里，
不要把一次逻辑改动拆成若干零碎提交。

### 6.2 提交前门禁

改完不等同于可提交。按改动类型逐项核对，任何一项未过都不提交。

| 改了 | 必须执行 | 通过判据 |
| --- | --- | --- |
| 任何 `app/src/main` 代码 | `./gradlew :app:testFullDebugUnitTest :app:testNosherpaDebugUnitTest` | 两个变体全部用例通过；新增逻辑补了对应用例 |
| 依赖、`proguard-rules.pro`、`build.gradle.kts`、版本号 | `./gradlew :app:assembleRelease` | 构建成功，两个变体 × 四个 ABI 共 8 个包都产出，签名配置生效 |
| 涉及时间、时区、格式化的断言 | 同上单测 + 固定 `ZoneId` | 显式写出时区，不依赖运行环境的默认值 |
| 任何文档 | 对照 [.agents/writing.md](.agents/writing.md) 的语体规则逐条核对 | 文案与源码字符串一致，链接与节号引用可达 |
| 端口、地址、路径类常量 | 实际执行一次相关命令 | 命令能跑通，不是照抄旧记录 |

构建一次只跑一个，两次构建之间留出间隔并检查 `free -h`（见第 1 节）。
