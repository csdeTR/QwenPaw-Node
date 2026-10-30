# Agent 约定

本文记录在本仓库工作时必须遵守的约定。第一节关于系统资源的条目属于**硬约束**，
不遵守会直接影响同机的桌面环境；其余各节属于工程约定，用于避免重复踩坑。

## 一、系统资源：硬约束

本机内存 15 GB、swap 8 GB，同时运行着 GNOME 桌面、Trae、QwenPaw 服务端与模拟器壁纸渲染。
曾经发生一次 **GNOME 整个卡死**，原因是同时运行了「Gradle daemon + Kotlin daemon + 模拟器」，
swap 被完全占满，桌面因 swap thrash 而失去响应。

以下八条必须遵守。

1. **不要连续执行构建与设备测试。**
   全量构建、`connectedAndroidTest`、`assembleRelease`（含 R8）都属于重活。
   应逐个执行，并在两次之间留出间隔，避免内存占用叠加。

2. **动手前先看内存。** 执行 `free -h`。可用内存低于 3 GB 时，
   应先关闭模拟器与多余进程，再开始构建。

3. **用完即关，不要保留常驻进程。**
   - Gradle daemon：`./gradlew --stop`
   - Kotlin daemon：`pkill -f KotlinCompileDaemon`
   - 模拟器：`adb emu kill`

   忘记收尾会导致下一次构建时内存基线偏高，进而挤占桌面环境。

4. **模拟器内存固定 2048 MB，不要调高。**
   启动参数保持 `-memory 2048 -no-window -gpu swiftshader_indirect`。
   调高内存会直接压缩宿主可用内存，与构建任务叠加时风险显著上升。

5. **优先增量构建。**
   只编译并装包时用 `./gradlew installDebug`；只跑单测时用 `./gradlew testDebugUnitTest`。
   不要顺手敲 `./gradlew build`，该目标会连带执行 lint、release 构建与全部测试。

6. **单测优先于设备测试。**
   设备端测试启动成本高，失败信息也差。能覆盖的逻辑一律写成 JVM 单测。

7. **不要同时启动多个 Gradle 实例。**
   命令应排队执行，不要并行开多个终端同时运行 `./gradlew`。

8. **设备验收一次只跑一个场景。**
   `scripts/e2e_acceptance.sh` 使用 `--only A4` 形式的单场景参数，跑完检查 `free -h`。
   不要整轮跑完再统一收尾，因为模拟器与构建同时运行极易拖垮桌面环境。

## 二、QwenPaw 服务端

- 本机实例运行在 `0.0.0.0:8088`，`auth.status.enabled=false`，可直接使用。
- 同机再启动第二个实例时使用独立 HOME，例如
  `HOME=/tmp/qp2 qwenpaw app --port 8099`。**用完必须 kill 并删除临时 HOME**，
  不要留下后台进程。
- 修改 `~/.qwenpaw/workspaces/default/agent.json` 前先备份，例如 `/tmp/agent.json.bak`，
  **测完立刻还原**。该文件的 `approval_level` 默认是 `AUTO`，只在测试审批时才临时改为 `ASK`；
  忘记还原会让后续所有真实使用都弹出审批。
- 测试会留下大量 running / idle 状态的会话。结束时应确认没有残留，
  必要时用 `POST /api/console/chat/stop?chat_id=` 收尾。
- **在真实会话上执行写操作要格外谨慎。** 历史上有过误操作把用户的真实会话归档的情况，
  虽然随后还原，但这类操作应当在动手前确认目标。

## 三、测试设备

**只使用模拟器测试。真机测试已停用，不要再尝试连接真机。**

### 3.1 模拟器

- AVD 名 `qp35`（Android 15，x86_64，1080x2400），启动参数
  `-memory 2048 -no-window -gpu swiftshader_indirect`。
- **启动前确认 `/dev/kvm` 存在。** x86_64 模拟必须有硬件加速，缺失时报
  `x86_64 emulation currently requires hardware acceleration`。
  本机曾出现内核模块未加载的情况，用 `ls -l /dev/kvm` 检查，
  不存在时执行 `sudo modprobe kvm && sudo modprobe kvm_intel`（AMD 用 `kvm_amd`）。
  CPU 是否支持可以先看 `grep -c -E "vmx|svm" /proc/cpuinfo`。
- **模拟器是 x86_64 架构。** 现在 `splits.abi` 已覆盖四个架构，直接装
  `app-x86_64-release.apk`（或 debug 对应 split）即可，**不再需要临时改 ABI 配置**。
  若本地把 `include()` 收窄过，记得还原，否则模拟器会报 `INSTALL_FAILED_NO_MATCHING_ABIS`。
- 冷启动时 System UI 偶发 `Application Not Responding` 弹窗，
  点 `Wait` 即可，不是应用崩溃。`uiautomator dump` 看到
  `package="android"` 且只有 `Close app` / `Wait` 两行文字时，就是这个弹窗。
- 可执行 `adb root`。断网场景用 `iptables`，因为 `svc wifi disable` 与飞行模式
  在模拟器里不起作用。**用完必须删除 iptables 规则**，否则后续调试全部连不上。
- 模拟器访问宿主机服务端用 `10.0.2.2:8088`，不是 `127.0.0.1`。
- 内存紧张时，模拟器与 Gradle 同时运行极易拖垮桌面。开模拟器前先 `free -h`，
  构建与模拟器不要长时间并行。

### 3.2 通用

- 操作界面时坐标一律从 `uiautomator dump` 动态获取，不要凭截图目测。
  截图是缩放的，按截图坐标点击会落空。
- `adb shell input text` 不支持中文，空格用 `%s`。
- 验收脚本：`ADB_SERIAL=<serial> ./scripts/e2e_acceptance.sh --only <A1..A6>`。
  在模拟器上跑时，A6 断网场景可用 `iptables`（模拟器可 `adb root`），
  真机时代因无 root 而自动跳过的逻辑不再适用。
- **截图与像素采样**：验证配色（如深色模式文字对比度）时，从 `adb exec-out screencap -p`
  取 PNG 后自行解码采样；环境里没有 PIL，需要手写 PNG 解码。

## 四、构建与签名

- Java 21 / AGP 8.7.3 / Kotlin 2.1.0 / compileSdk 35。
- release 签名密钥在仓库之外：`~/.qwenpaw-node-keys/`
  （`release.jks` 与 `keystore.properties`）。**不要复制进仓库**，
  `.gitignore` 已排除 `*.jks` / `*.keystore` / `keystore.properties`。
- release 开启了 R8 与资源压缩。改动依赖后必须重新验证一次 `assembleRelease`，
  不能只验 debug，因为部分问题只在 R8 处理后暴露。
- **镜像仓库按环境切换。** `settings.gradle.kts` 在 `CI=true` 时跳过阿里云镜像，
  只使用 `google()` / `mavenCentral()` / `gradlePluginPortal()`，因为镜像在
  GitHub Actions 的 runner 上不可达。Trae 的 shell 也带 `CI=true`，
  在其中构建走的是官方仓库，与用户自己的终端不同。
- **CI 签名密钥来自四个 Repository secrets**：`ANDROID_KEYSTORE_BASE64`、
  `ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`。
  推 `v*` tag 触发 `.github/workflows/release.yml`，细节见 `docs/release.md` 5.3。
- **在这台机器上执行 `git push` 时加 `GIT_ASKPASS=/bin/false GIT_TERMINAL_PROMPT=0`。**
  Trae 注入的 askpass 脚本在没有交互终端时会永久等待，表现为命令超时而非报错。
- 相关文档：协议细节与已知坑见 `docs/protocol.md`；UI、边到边与性能测量口径见
  `docs/ui-guidelines.md`；目录与数据流见 `docs/architecture.md`；
  发版流程见 `docs/release.md`。

## 五、文档

### 5.1 语体分档

文档按受众分两档，语体不同。修改文档前先确认它属于哪一档。

| 档位 | 文件 | 语体要求 |
| --- | --- | --- |
| **技术档** | `docs/architecture.md`、`docs/protocol.md`、`docs/ui-guidelines.md`、`docs/release.md`、`docs/devlog.md`、`AGENTS.md`、`待办清单.md` | 正式书面语 |
| **用户档** | `README.md`、`CHANGELOG.md`、`docs/guide.md` | 书面语，适度口语化 |

**用户档的定位：以书面语为主，只是把难度降下来，不是把语气变成聊天。**

判据是「书面语是否仍然成立」。说明文、手册、变更记录都应当保持书面语的句子结构与用词，
在此基础上做三件事：换成普通读者认识的词、把长句拆短、把抽象说法换成具体说法。
不应当把书面语改写成口语对话。

**技术档规则**

1. 使用完整句陈述，不用「支持 X」「进行 Y」这类名词化懒句。
2. 每个结论必须带依据：实测数据、代码位置或服务端字段名。
3. 保留术语原形（`statusBarsPadding`、`inSampleSize`、`msg_id`），不做中文意译。
4. **信息密度可以高，但不许为了顺口而删事实。** 重写后必须对照原文件逐条核对。
5. 不用第一人称叙事，不用「我们」。

**用户档规则**

1. 保持书面语。可以出现「请确认」「需先」「属于预期表现」这类表述，
   不要降格为「你试试」「就行了」「卡是正常的」。
2. 使用第二人称「你」或无人称叙述均可，但避免「我们」。
3. 句子以短句为主，一句说一件事；不为了简短而省掉必要成分（主语、条件、范围）。
4. 用普通读者认识的词，但不牺牲准确性。不确定的地方保留「约」「通常」「多数情况下」。
5. 不用「强大、流畅、极致、全方位、赋能」这类空泛形容词。
6. 不用客服腔，例如「感谢反馈」「如有疑问欢迎随时联系」。
7. 不用网络口语与语气词：「搞定」「卡是正常的」「看一眼」「点一个选上就行」。
8. 不硬凑三段式。有两项就写两项。
9. 界面上的按钮与开关一律用「」包住，与正文区分。
10. 技术细节不删，但放到段末，或加链接指向技术档。
11. 不编造功能。只写代码里真实存在的界面项，文案要与源码字符串核对。

### 5.2 开发日志必须随时更新

`docs/devlog.md` 是按时间顺序记录过程与决策的日志。

**每完成一轮有实质产出的工作，就立刻追加一节，不要攒着。**

1. 只记有信息量的内容：决策理由、被推翻的假设、踩过的坑、实测数据。
   功能清单那种流水账交给 `CHANGELOG.md`，不要重复。
2. **弯路要写。** 这一路最有价值的内容恰恰是「哪些方向已经被证明是错的」。
   不写下来，下一个人或下一轮会重走一遍。
3. 数字要当场取（`ls -la`、`wc -l`、`git log`），不要凭记忆或照抄上一版。
   本项目已经在体积、单测数、APK 大小上错过多次。
4. 追加时同步更新文首的目录表，并确认锚点仍然可跳。
5. 写过程与理由，不写「已完成 X 功能」这类结论式套话。
6. 每节末尾尽量指明结论现在落在哪份正式文档，便于从日志跳转到结论。

### 5.3 每轮改动的草稿

对项目的每一次改动，都先在项目目录的 `draft/` 下创建一个以时间戳命名的 Markdown 文件，
作为这一轮的草稿。所有过程性内容只写进草稿。

**命名格式**：`draft/YYYY.MM.DD-HH·MM·SS.md`，例如 `draft/2026.10.26-14·07·33.md`。

时间取**用户发起这一轮对话的时刻**，而不是开始动手的时刻。
日期用点号分隔、时间用间隔号分隔，目的是避开文件名中不允许出现的冒号。
若一轮对话跨越较长时间，仍沿用发起时刻，不因中途继续而改名。

**草稿内容**：这一轮要改什么、为什么改、涉及哪些文件、验证结果、尚待确认的问题。
按讨论推进逐步补全，不要求一次写完整。

**草稿不纳入版本管理。** `.gitignore` 已排除 `draft/`，不要提交其中的任何文件。

### 5.3.1 完成之前不许动正式文档

**这是硬性顺序，不是建议。**

在一轮任务完成之前，不得修改 `docs/`、`README.md`、`CHANGELOG.md`、`待办清单.md`
等任何正式文档。中间产物一律留在草稿里。

原因不是谨慎，而是防污染。上下文会被压缩、会丢失、会被后续对话覆盖，
如果一边做一边改文档，被压缩掉的中间状态就会以确定语气留在文档里，
读者无法分辨哪些是已核实的结论、哪些是中途被推翻的设想。
本项目已经出现过这种情况，文档因此出现与代码不符的表述。

顺序固定为：

1. 改动开始前创建草稿，边做边写。
2. 编码与验证全部结束、结论确定之后，才一次性把结论**提炼**进正式文档与 `docs/devlog.md`。
   提炼规则见 5.1 的语体分档与 5.2 的日志要求。
3. 正式文档写完并核对无误后，**删除**草稿。

**草稿不能替代正式文档。** 它是过程载体，结论必须落到 5.4 表中对应的文件里；
只写草稿而没更新正式文档，等于这一轮没有沉淀。

### 5.4 各文档分工

| 文件 | 定位 | 语体 | 更新时机 |
| --- | --- | --- | --- |
| `draft/YYYY.MM.DD-HH·MM·SS.md` | 本轮改动的草稿，不入版本管理 | 不限 | **每轮改动开始时创建，正式文档写完后删除** |
| `docs/devlog.md` | 过程与决策记录 | 技术档 | **每轮工作结束立刻追加** |
| `CHANGELOG.md` | 面向用户的变更 | 用户档 | 发版前 |
| `README.md` | 仓库首页，面向使用者 | 用户档 | 功能或安装方式变化时 |
| `docs/guide.md` | 使用手册，面向使用者 | 用户档 | 界面或操作流程变化时 |
| `docs/protocol.md` | 服务端协议实测与坑 | 技术档 | 发现新端点或新坑时 |
| `docs/ui-guidelines.md` | UI 约定与性能口径 | 技术档 | 新增 UI 约定或测量方法时 |
| `docs/test-plan.md` | 分层测试用例与覆盖缺口 | 技术档 | 新增功能、补测试或确认疑点时 |
| `docs/architecture.md` | 分层与状态流转 | 技术档 | 结构调整时 |
| `docs/release.md` | 发版流程 | 技术档 | 发版流程变化时 |
| `待办清单.md` | 服务端已提供、客户端未接 | 技术档 | 接入一项后划掉 |

**对话记录不是项目文档。** 不要把过程记录当成结论文档去改。
要沉淀的内容应提炼进上面这些文件；`README.md` 与 `docs/guide.md` 里不要放构建命令与发版细节。