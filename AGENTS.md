# AGENTS.md

本文只写每轮都要生效的约定与判据。操作步骤、环境细节与写作规范放在归属文档里，正文留一行指针。

**本文上限 100 行、6,000 字符**（按 `wc -l` / `wc -m` 计），整份每轮加载。新增规则须同时满足：能指名一次真实事故、门禁失败或已发生的误操作；删掉后资深工程师确实会做错。归属文档已有同一规则的，本文只留一行指针；在本文添加规则时，同轮删去归属文档里的重复表述。

## 0、协作方式

这一节约束工作方式而非工程细节，因此放在最前。

1. **用户给出的路线、方案、实现不等于最优解。** 有更合适的做法时，先说明好处、代价与差异，由用户选择；提出异议必须带依据（实测数据、代码位置、服务端字段名），不能只表达偏好。
2. **需要确认的地方先提问。** 需求含糊、涉及破坏性操作、要在多种合理实现之间取舍、或改动会越出本轮范围时，先问再做。判断标准是「做完才发现方向不对」的返工成本是否高于一次提问的成本。
3. 提问要收敛：一次给出二到四个可选项，每项写明后果，推荐项放第一位。

## 1、系统资源

本机处理器为较老旧的 i5-4590，内存 16 GB、swap 8 GB，同时运行着 GNOME、Trae或QoderCN、QwenPaw 服务端与动态壁纸等。曾因「Gradle daemon + Kotlin daemon + 模拟器」同时运行、swap 占满而整机卡死，桌面因 swap thrash 失去响应。

以下八条必须遵守。

1. **不要连续执行构建与设备测试。** 全量构建、`connectedAndroidTest`、`assembleRelease`（含 R8）都是重活，逐个执行，两次之间留出间隔。
2. **动手前先看内存。** `free -h` 的「可用内存」与「swap 已用」两项都要看：可用内存低于 3 GB，或 swap 已用超过 4 GB，先关掉测试设备、测试容器与多余进程再开始构建。只看 available 不够，swap 过半说明系统已在换页。
3. **用完即关，不要保留常驻进程。** 测试设备与测试服务端都按需启动，用完立即关闭。
   - Gradle daemon：`./gradlew --stop`
   - Kotlin daemon：`pkill -f KotlinCompileDaemon`
   - redroid 容器：`docker compose -f scripts/redroid/docker-compose.yml down`
   - LXC 测试容器：`lxc stop qp-test qp-test-prerelease`（只停容器，LXD 服务与 `lxdbr0` 保留，下次直接 `lxc start`）
   - AVD：`~/Android/Sdk/platform-tools/adb -s emulator-5554 emu kill`

   不收拾会让下一次构建的内存基线偏高。
4. **redroid 容器的资源上限固定在 compose 里，不要调高。** 3 GB 内存、2 CPU、4096 pids；不设上限时容器曾把 swap 吃满。**也不要换回 Android 12 镜像**：Android 15 起强制边到边，旧版本会得出相反结论。依据见 [internal/test-env.md](internal/test-env.md) 第 2 节。
5. **优先增量构建。** 装包用 `./gradlew installFullDebug`，单测用 `:app:testFullDebugUnitTest` / `testNosherpaDebugUnitTest`。不要敲 `build`（连带 lint、release 与全部测试），也不要 `assembleDebug` / `assembleRelease`（一次构建 8 个包）。
6. **单测优先于设备测试。** 设备端测试启动成本高、失败信息差，能覆盖的逻辑一律写成 JVM 单测。
7. **不要同时启动多个 Gradle 实例。** 命令排队执行，不要并行开多个终端运行 `./gradlew`。
8. **设备验收一次只跑一个场景。** `scripts/e2e_acceptance.sh` 用 `--only A4` 形式的单场景参数，跑完检查 `free -h`；整轮跑完再统一收尾会让测试容器与构建叠加，极易拖垮桌面环境。

## 2、服务端：只用测试容器

**测试一律使用 LXC 容器，不使用本机的 `:8088` 实例。** 本机实例承载用户的真实会话与记忆，在上面做写操作会污染真实数据，历史上有过误归档真实会话的教训。

| 容器 | IP | 版本 | 用途 |
| --- | --- | --- | --- |
| `qp-test` | `10.10.0.211:8088` | `2.3.0.post1` | 稳定版基线，与用户当前使用的版本一致 |
| `qp-test-prerelease` | `10.10.0.220:8088` | `2.3.0.post1` | 预发布通道；2026-11-19 更新后暂时同版本 |

- 启动、停止、探活、`lxdbr0` 排查、审批档位与还原核对：见[internal/test-env.md](internal/test-env.md) 第 1 节。
- **在 Qoder 沙箱里用 `scripts/lxd.sh` 代替 `lxc`**（`/snap/bin/lxc` 会因 `snap-confine` 缺权限直接失败）；原因与用法见 test-env 第 1.1 节。
- 两版端点差异、404 与 405 判据、无数据实例上的 `null` 形状见[docs/protocol.md](docs/protocol.md) 第 17 节。客户端使用的核心路径在各版本一致（2026-11-19 复测 156 组），**不做运行时版本分支**。

## 3、测试设备

**真机测试已停用，不要再尝试连接真机。** 首选 redroid 容器，redroid 不可用时回退 AVD `qp35`。两者的显示参数一致：720×1600、280dpi。

- 启动步骤、A6 断网、到服务端的网络路径、验收脚本与截图采样：见[internal/test-env.md](internal/test-env.md) 第 2–4 节。
- 三条硬性约束留在本文第 1 节：不改 compose 的资源上限、不换回 Android 12 镜像、用完即关。

## 4、构建与签名

- 技术栈、常用命令与 release 构建的特殊之处见 [internal/release.md](internal/release.md) 1.1–1.4；签名密钥的位置与「构建成功不等于包可用」的判据见第 2 节。
- **新增或升级依赖必做两件事。** 补 `app/proguard-rules.pro` 的 keep 规则；复核两个变体 × 四个 ABI 的包体积（`sherpa-onnx` 的 `.so` 每架构 14–30 MB，只进 `full`，四个 ABI 都要看）。见 release.md 1.4 与 7。
- **涉及时间与时区的单测必须显式固定 `ZoneId`。** 本机 `Asia/Shanghai`、runner 为 UTC，写法参照 `TimeLabelsTest`；原因见 release.md 1.5。
- **镜像仓库按环境切换。** `CI=true` 时只用 `google()` / `mavenCentral()` / `gradlePluginPortal()`；Trae 的 shell 也带 `CI=true`，复现 runner 行为用 `CI=true ./gradlew <task>`。见 release.md 1.5。
- **本机执行 `git push` 要加 `GIT_ASKPASS=/bin/false GIT_TERMINAL_PROMPT=0`。** Trae 注入的 askpass 在无交互终端时会永久等待，表现为超时而非报错。
- **debug 与 release 用不同包名，可同机共存。** debug 为 `io.qwenpaw.node.debug` / `[debug]QP Node`，release 为 `io.qwenpaw.node` / `QwenPaw Node`；验收脚本默认按 debug 执行，可用 `ADB_PKG` 覆盖。
- **语音有两个构建变体。** `full` 带端上识别引擎、可二选一来源，`nosherpa` 只能服务端转写；`applicationId` 相同、不能同机共存。见 release.md 7.1；改动 `voice/` 时两个变体都要编译与跑单测。

## 5、文档

- 每轮先在 `draft/` 下建一个 `YYYY.MM.DD-HH·MM·SS.md` 草稿，时间取用户发起这一轮的时刻；过程性内容只写草稿，避免因上下文长度限制引发的问题。
- **结论确定之前，不得修改 `docs/`、`README.md`、`CHANGELOG.md`、`待办清单.md`**：中途被压缩掉的状态会以确定语气留在文档里，读者无法分辨已核实的结论与被推翻的设想。
- 顺序固定：建草稿 → 编码与验证 → 把结论提炼进正式文档与 `internal/devlog.md` → 删除草稿。
- **`internal/` 整个目录不进版本管理**，只保留在本地（开发日志、环境操作、写作约定、发版流程、待办清单）。公开文档引用其中结论时用文字描述（「发版记录」「开发日志第 67 节」），**不要写成链接**。
- 语体分档、开发日志写法、各文档分工、界面文案：见 [internal/writing.md](internal/writing.md)。
- **界面文案一律用书面语，副标题与灰字说明同样算界面文案。** 「公式也不渲染」这类口语属于必须回查的一类缺陷，改写方式见[internal/writing.md](internal/writing.md) 第 4 节。

## 6、提交与门禁

### 6.1 提交信息

仓库只有 `master` 一个长期分支，提交信息统一为「前缀: 中文描述」。前缀取 `feat`（功能）、`fix`（修复）、`test`（测试）、`docs`（文档）、`build`（构建、依赖、版本号、ProGuard）、`ci`（工作流）、`chore`（杂项）。

描述写**做了什么**，不写「更新代码」这类无信息量的说法；同一轮的多个文件放在一个提交里，不拆成若干零碎提交。

### 6.2 提交前门禁

改完不等同于可提交。按改动类型逐项核对，任何一项未过都不提交。

| 改了 | 必须执行 | 通过判据 |
| --- | --- | --- |
| 任何 `app/src/main` 代码 | `./gradlew :app:testFullDebugUnitTest :app:testNosherpaDebugUnitTest` | 两个变体全部用例通过；新增逻辑补了对应用例 |
| 依赖、`proguard-rules.pro`、`build.gradle.kts`、版本号 | `./gradlew :app:assembleRelease` | 构建成功，两个变体 × 四个 ABI 共 8 个包都产出，签名配置生效 |
| 涉及时间、时区、格式化的断言 | 同上单测 + 固定 `ZoneId` | 显式写出时区，不依赖运行环境的默认值 |
| 任何文档 | 对照 [internal/writing.md](internal/writing.md) 的语体规则逐条核对 | 文案与源码字符串一致，链接与节号引用可达 |
| 端口、地址、路径类常量 | 实际执行一次相关命令 | 命令能跑通，不是照抄旧记录 |

### 6.3 上游与发布

**未经用户明确确认，不得 `git push`、不得打 tag 或发布 Release。** 本地提交可以；推送、tag 与 Release 必须逐次确认。撤回步骤见 [internal/release.md](internal/release.md)。
