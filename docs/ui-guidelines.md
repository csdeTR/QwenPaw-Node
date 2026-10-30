# UI 约定与性能测量口径

本文记录界面层的跨页面约束、已验证的错误做法，以及性能数据的测量方法。
这些条目全部来自实际调试，修改界面之前应通读。

分层与状态流转见 [architecture.md](architecture.md)；服务端接口与失败模式见 [protocol.md](protocol.md)。

---

## 一、边到边（edge-to-edge）适配

### 1.1 背景

`targetSdk = 35`。从 Android 15 起，系统对声明该 targetSdk 的应用**强制**边到边：
状态栏与导航栏变为透明，浮在应用内容之上。应用不再能通过系统自动避让获得留白。

实测环境为红米 K80（HyperOS，Android 15）。修复前，除设置页外的所有页面都被通知栏与手势条压住。

### 1.2 根因：Scaffold 的一条隐式约定

M3 `Scaffold` 的 `ScaffoldDefaults.contentWindowInsets` 取值是 **systemBars**，
但它注入的内容留白遵循一条容易忽略的条件：

> **只有当对应的 bar 槽位为空时，Scaffold 才把该方向的系统栏留白算进内容区。**

由此产生两种截然不同的结果：

- 设置页使用**不带** `topBar` / `bottomBar` 的 `Scaffold`，槽位为空，Scaffold 自动注入留白，因此显示正常；
- 其他页面把自定义栏传给 `topBar` / `bottomBar`，Scaffold 认为「这一方向由你自行处理」，不再注入留白，
  而自定义栏本身又没有处理，于是内容被系统栏压住。

用户反馈中「设置页除外」这一条线索，正是这个机制的直接体现。

### 1.3 规则

**底色仍然铺满系统栏区域（保持真边到边），只把内容让开。**

- **顺序不能颠倒：先 `.background(...)`，再 `.statusBarsPadding()`。**
  如果反过来写，留白区域位于背景之外，会显示父容器的底色，
  屏幕顶部因此出现一条与其他区域颜色不同的色带。这是最容易出现的视觉瑕疵。
- 自定义顶栏一律补 `.statusBarsPadding()`。
- 自定义底栏补 `.navigationBarsPadding()`。
- 需要同时避让键盘与手势条时，用 `NavigationBars ∪ Ime` **取并集**
  （`WindowInsets.navigationBars.union(WindowInsets.ime)`），**不要相加**。
  键盘高度本身已经包含手势条占用的部分，两者相加会把内容顶高出一截，
  表现为键盘弹出后输入条与键盘之间出现一段空隙。
- 留白要加在**有背景色的那个容器内部**。例如 `ChatInputBar` 的 inset 加在 `Surface` 里面；
  若加在 `Surface` 外面，Surface 之外会露出一条页面底色。
- **裸页面**（没有被 Scaffold 包裹的页面，如 `ConnectScreen`）自行补
  `statusBarsPadding()` + `navigationBarsPadding()`。
  留白要加在**页面这一层**，不要加进被复用的子表单（`InstanceForm`）内部。
  原因是设置页也复用该表单，而那边已经由 Scaffold 注入留白，加在表单内部会造成双重留白，
  表现为顶部出现一块明显多余的空间。
- 对话框走 Compose `Dialog`，其默认 `decorFitsSystemWindows = true`，不受边到边影响，无需处理。

### 1.4 已适配的位置与对应现象

| 文件或位置 | 处理方式 | 未处理时的界面现象 |
| --- | --- | --- |
| `ChatTopBar` | `statusBarsPadding()` | 会话标题与返回按钮压进通知栏，被状态栏图标遮挡 |
| `ChatListScreen.ListHeader` | `statusBarsPadding()` | 列表页标题与搜索框贴近屏幕顶边 |
| `ChatListScreen` 新建按钮 / 批量操作条 | `navigationBarsPadding()` | 按钮压住手势条，滑动返回手势与点击冲突 |
| `ChatInputBar` | `Surface` 内部使用 `navigationBars ∪ ime` | 发送按钮被手势条覆盖；键盘弹出时输入条与键盘之间出现空隙 |
| `ArchivedScreen` / `TokenUsageScreen` | `statusBarsPadding()` | 标题压进通知栏 |
| `ConnectScreen` | 外层 `statusBarsPadding()` + `navigationBarsPadding()` | 连接表单顶部贴边、底部按钮压住手势条 |

### 1.5 验收基准

测量环境：模拟器 1080×2400、420dpi、手势导航。该环境下状态栏高 128px，手势条高 63px。

| 位置 | 期望值 |
| --- | --- |
| 聊天页标题 | 起始 y ≈ 154，位于状态栏下沿（128）之下 |
| 聊天页输入条按钮 | 底边 y = 2293，位于手势条上沿（2337）之上 |
| 列表页顶栏 | y ≈ 158 |
| 列表页「新建会话」 | y = 2224–2277 |
| 键盘弹出 | 输入条上移至 1424–1469，与键盘之间无空隙 |

状态栏区域应填的是**应用自身的底色**（浅色主题下为 `#F9F8F4`）。
若该区域显示为纯白或与页面其他部分不一致，说明背景没有铺满，或修饰符顺序写反了。

> 以上坐标仅在上述分辨率与 dpi 下有效。换设备后应重新从 `uiautomator dump` 取值，
> 不要沿用本文数字。

---

## 二、重组作用域

### 2.1 不要把 `derivedStateOf` 用 `by` 委托在页面作用域读取

`derivedStateOf` 会在其依赖的状态变化时重新计算，并把连续变化折叠为少量翻转。
它的价值正来自「折叠」：滑动过程中滚动位置每帧都在变，但「是否在底部」这个布尔值只在越界时才翻转。

问题在于**读取位置**。若在页面的组合作用域中用 `by` 委托读取，
这个布尔值一旦翻转，就会重组该作用域下的全部内容。

`ChatScreen` 曾因此产生实际开销：每次滑动开始与结束时，`atBottom` 都会翻转一次，
导致整屏可见条目连同 Markdown 正文被重组两遍。
用户感知为「一开始滑动就顿一下，停下来又顿一下」。

正确做法有两条，任选其一：

- 保留 `State` 对象（`remember { derivedStateOf { ... } }`），在**真正需要的位置**
  显式读取 `.value`，例如在协程内部判断；
- 把读取点封装进更小的组件内部，让重组范围收敛到该组件。

### 2.2 滚动相关的高频状态优先用 `derivedStateOf`

与之相对，滚动位置、能否继续滚动这类每帧都在变化的值，必须先用 `derivedStateOf` 折叠，
再交给 UI 使用。直接用原始滚动状态驱动 UI，会让每个滚动帧都触发一次重组。

---

## 三、列表与缓存

### 3.1 昂贵解析必须 `remember`

列表条目滚出视口后会被销毁，滚回来时重新执行组合。
因此 `MarkdownParser.parse`、行内样式 `AnnotatedString`、表格单元格内的行内样式，
都必须按内容 `remember` 缓存。

不做缓存的后果不是「稍微慢一点」，而是**每次滚回同一段内容都要重新完整解析一遍**，
在长列表中表现为持续的主线程开销，越往回滚越明显。

### 3.2 解析期的 `Regex` 必须提到文件级常量

`java.util.regex.Pattern` 的编译不便宜。把 `Regex("...")` 写在循环体内，
会让每一行文本、每一个表格单元格都重新编译一次正则。

本项目的 `MarkdownParser` 曾有两个正则写在循环里：

- `^\d+[.)]\s+.*`（有序列表判定）
- `^:?-{2,}:?$`（表格分隔行判定）

后者在一张十几行的表格上会被执行几十次。现已提升为文件级常量。

### 3.3 不要在重组中新建格式化对象

`SimpleDateFormat`、`Calendar`、`Scanner` 这类对象不应在每次重组时新建。
日期展示改用 `java.time.LocalDate`，并按时间戳 `remember` 缓存结果。

会话列表的时间标签曾因此产生明显开销：每次滑动、每一行都执行一次日期解析。

---

## 四、性能测量口径

### 4.1 模拟器上的掉帧百分比不可信

在 `-gpu host` 模式下（宿主 GPU 为 GTX 950），RenderThread 单帧 `Drawing` 可达 45ms，
`dequeueBuffer` 出现阻塞。此时掉帧百分比完全由 GPU 主导，无法反映客户端代码的差异。
用软件渲染（swiftshader）时情况更极端，GPU 项直接出现 4950ms 的量级。

**识别特征**：`dumpsys gfxinfo` 的直方图里 GPU 项远大于 CPU 项，
或 RenderThread 的 `Drawing` 单帧耗时超过 30ms。出现这种情况时，该次测量作废。

### 4.2 使用与 GPU 无关的主线程帧指标

正解是取 `dumpsys gfxinfo <pkg> framestats` 中
`HandleInputStart → PerformTraversalsStart` 的间隔。

Compose 的 doFrame 回调在 `performTraversals` 之前完成，
因此这一段就是「一帧里主线程真正花掉的时间」，与 GPU 无关，可以在模拟器上做 A/B 对比。

### 4.3 atrace 的两个坑

**必须带包名。** `atrace --async_start -c gfx,input,view` 采不到应用级 trace section，
`AndroidOwner:measureAndLayout`、`Compose:recompose`、`Recomposer:animation`、
`compose:lazy:prefetch:*`、`TextLayout:initLayout` 这些都不会出现。
必须写成 `atrace --async_start -a io.qwenpaw.node -c gfx,input,view`。

**解析时 `B|<pid>|` 里的 pid 是进程 id，不是线程 id。**
`tracing_mark_write: B|<pid>|` 中的这个数字标识的是进程，
而嵌套栈的配对必须用行首 `comm-tid (pid)` 里的 **tid** 作为 key。

**识别特征**：解析脚本算出的总时长为 0.0ms，或某个 section 的累计时长出现数量级异常
（例如远超采集窗口长度）。两者都说明栈 key 用错了。

---

## 五、debug 与 release 的差距

### 5.1 数据

**debug 包明显更慢，评估流畅度必须使用 release 包。**

debug 变体 `isMinifyEnabled = false`：没有 R8 的内联与优化、没有 AOT、
没有 baseline profile，Compose 内部还会走 debug 断言路径。

同一份源码、同一会话（171 条消息）、同一手势，取主线程帧耗时：

| build | p50 | p99 | max |
| --- | --- | --- | --- |
| debug（三轮） | 1.41 / 1.75 / 1.72 ms | 26.8 / 4.7 / 3.7 ms | **43.3 ms** |
| release（三轮） | 0.77 / 0.65 / 0.68 ms | 2.7 / 2.0 / 11.4 ms | **11.9 ms** |

release 的主线程帧耗时约为 debug 的 **43%**，长帧从 43ms 降到 12ms。

debug 构建下的 atrace 分片可以印证这一点：`AndroidOwner:measureAndLayout` 累计 440–500ms
（单次最大 145.6ms）、`Compose:recompose` 208–244ms、`TextLayout:initLayout` 出现上百次。

在宿主机这种较快的 CPU 上，debug 的 p50 只有 1.4–1.75ms，看起来无碍。
但换算到中低端手机的 CPU 上，这个基数会被成倍放大，一旦跨过 16.67ms 的帧预算，
用户就会感知到卡顿。

> `scripts/e2e_acceptance.sh` 安装的是 **debug** 包。
> 因此「脚本通过」不等于「用户手上流畅」。

### 5.2 已被实测排除的方向

以下四个假设都曾被认为可能是滚动卡顿的原因，均已用实测否定。
再次遇到卡顿时，不要先往这些方向投入。

| 假设 | 当时为什么会产生 | 实测结论 |
| --- | --- | --- |
| 历史一次铺开太多条 | 长会话有 500 条以上消息，直觉上认为条目数量是负担 | LazyColumn 只组合可见项。对比窗口化 8 条与铺开 500 条，滚动时可见项数量一致，无差异 |
| `SelectionContainer` 嵌套导致指针节点翻倍 | 每个气泡都包了选区容器，怀疑指针事件开销 | 仅约 8%，属于噪声级 |
| Markdown 解析与正则新建是主因 | 聊天页每条消息都要解析 Markdown，看似重活 | 微基准 0.113ms/条、正则 1μs/次，量级不足 |
| `atBottom` 在父组合作用域被 `by` 读取 | 确实存在作用域过大的问题，且修改后有可测收益 | 重组从 221ms 降到 208ms（约 10%），`measureAndLayout` 无变化，因此不是根因 |

这四条中有三条本身是真实存在的问题，只是**量级不足以解释用户感知到的卡顿**。
把它们与真正的根因（构建类型）区分开，是这一轮排查的主要工作量。

---

## 六、字号缩放

UI 缩放与聊天正文缩放是**两条独立的链**：

- **UI 缩放**（`uiScale`）作用于整套 `Typography`，由 `QwenPawTheme` 提供。
  影响标题、按钮、列表等界面元素。
- **聊天缩放**（`chatScale`）只叠加在消息正文上，由 `ChatFontScale` 作用域包裹列表。
  顶栏与输入框仍使用 UI 字号。

两者都是连续值，不是档位枚举。

`Typography` 的构造开销不低，必须按缩放系数 `remember` 缓存；否则每帧重建整套字体定义。

**自定义固定字号必须跟随 `LocalChatFontScale` 一起缩放**，包括标题与代码块。
若使用硬编码的 `sp` 值，用户把聊天字号调大之后，这些元素会相对显得偏小，
整体排版失去一致性。

---

## 七、时间显示与列表分组

### 7.1 服务端时间是 UTC，必须折算到本地时区

服务端给的是 UTC ISO 8601（`2026-10-29T02:13:41.029Z`，个别版本不带 `Z`）。
早期实现直接截字符串前 10 位当日期，等于按 **UTC 日界**划分「今天／昨天」：
东八区早上 7 点发的消息会被算成前一天。这是实际存在的偏差，不是理论风险。

统一入口是 `ui/TimeLabels`：

| 方法 | 用途 |
| --- | --- |
| `parse(raw)` | 解析到本地时区的 `ZonedDateTime`，失败返回 null |
| `timeOnly(raw)` | 消息气泡头部的 `HH:mm` |
| `relativeDay(raw)` | 分组用：今天 / 昨天 / `M月d日` / 完整日期 |
| `listStamp(raw)` | 会话列表右上的时或短日期 |

日期差用**本地日历日**相减，不用 24 小时差：用户说的「昨天」是日历概念，
跨夏令时或中途换时区时按小时算会偏。

### 7.2 分组三态

`GroupMode` 三档与 QwenPaw 网页版一致：按时间、按来源、不分组。单击按钮循环，
顺序 `TIME → SOURCE → NONE`，默认 `TIME`。

`ChatGrouping` 是纯函数，无 Compose 依赖，因此 `ChatGroupingTest` 能在 JVM 上覆盖
跨日边界、缺时间、未知分组这类情况。

两条贯穿两种模式的规则：

1. **置顶项单独成区并排最前。** 置顶的意义是「永远在我手边」，
   若按时间桶散落到「近 7 天」里，等于没有置顶。
2. **空桶不输出。** 只有表头没有内容的分区是噪声。

另有一条交互规则：**搜索时强制不分组**。分组会把命中的会话拆到多个表头下，
反而更难扫读。

表头用 `stickyHeader` 吸顶；不分组模式下只有一个无标题分区，表头不渲染。

---

## 八、消息气泡的结构

### 8.1 三段式

用户消息整体靠右、头像在最右；智能体消息整体靠左、头像在最左。上下结构一致：

```
[头像]  [昵称]          ← 第一行
        [2021.09.21 10:43:20]  ← 第二行，完整时刻
        ╭──────────╮
        │  气泡内容  │
        ╰──────────╯
        [复制] [复制为 Markdown]
        [输入 N · 输出 M · 模型 tokens]
```

- 头像 32dp 圆形。没有自定义头像时渲染「昵称首字 + 按名字取色的底色」——
  一个空圆圈比首字更难分辨谁是谁。同一名字永远得到同一颜色。
- **头像与昵称同处头部行，内容列独占整宽。** 头像若与内容同处一个 `Row`，
  内容列就必须让出 `8dp + 32dp` 的头像宽度，用户气泡的右侧、智能体气泡的左侧
  各留一条空白，气泡永远贴不到屏幕边缘。内容列用 `fillMaxWidth()`，
  头像不参与内容宽度计算。
- 昵称：用户侧取设置里的名字（默认「我」），智能体侧优先用消息自带的
  `metadata.original_name`，缺失时退回实例的智能体名。
- **昵称与时刻分两行，不合成一行。** 合成一行时昵称一长就只剩时间，或者时间被截掉；
  分开后两者各自完整，且用户侧右对齐、智能体侧左对齐，与头像位置一致。
- 时刻用 `TimeLabels.fullStamp()`，格式 `yyyy.MM.dd HH:mm:ss`。
  用户侧用创建时间，智能体侧优先用完成时间。
- **流式期间隐藏时间与复制按钮**。那时 `finished_at` 还没到，显示创建时间会让人
  以为回复已结束；复制也会复制到半句话，且按钮每帧抖动。
- 用量行只在该轮收尾消息上出现，且受设置开关控制。

**时间解析必须兼容带偏移量的字符串。** 服务端的 `metadata.timestamp` 形如
`2026-10-26T00:15:42.910613+08:00`，`Instant.parse` 在 Android 的 `java.time` 上
不接受偏移、会返回 null。`TimeLabels.parse()` 因此按 `Instant` → `OffsetDateTime`
→ 补 `Z` 的顺序依次尝试。**这类 bug 单测发现不了**，因为测试样本通常都是 `Z` 结尾。

### 8.2 为什么统计只挂一条

服务端把整轮的 `qwenpaw_turn_usage` 重复挂在**该轮每一条** assistant 消息上。
若逐条渲染，一个多步回合里每段正文下面都会重复同一组数字，
看起来像每段话都花了那么多。`HistoryMapper.markTurnEnd()` 按回合去重，
只让该轮最后一条带统计的正文保留。

### 8.3 头像规格

静态图裁剪为正方形后压到 500×500 PNG（不是 JPEG：头像多为带透明背景的截图，
JPEG 会把透明区染成黑色）。GIF 原样保存，由 `AvatarStore.imageLoader` 逐帧播放。

逐帧解码必须显式注册，否则 `AsyncImage` 只画第一帧：

| API | 解码器 | 说明 |
| --- | --- | --- |
| 28+ | `coil.decode.ImageDecoderDecoder` | 走系统 `ImageDecoder`，动画由框架驱动 |
| 26–27 | `coil.decode.GifDecoder` | Coil 自带 `Movie` 逐帧播放 |

minSdk 是 26，两条分支都必须留。静态 PNG 与 GIF 走同一个 `AsyncImage`，没有第二条渲染路径。

首字回落只在图片就绪前显示。图片就绪后不再叠在底下：带头像的 GIF 常有透明区域，
首字会从透明处透出来。加载失败（文件损坏、格式不支持）退回首字，不留空洞。

头像存在 `filesDir/avatars/`，不上传服务端。`AvatarStore.version` 是 StateFlow，
任何一次写入或删除都会 +1，界面据此重新读文件——设置里换完头像返回聊天页靠它生效。

### 8.4 选区配色与长按手势

**选区底色不得与它能覆盖的任何气泡底色同系。** 用户气泡底色是品牌橙
（实测 `rgb(255,127,22)`），若选区取 `colorScheme.primary`（同为橙），
叠上去完全看不见，用户会以为「选中失败了」。`Theme.kt` 因此单独给
`LocalTextSelectionColors` 一套蓝系配色：浅色 `#1F6FEB`、深色 `#7AA7FF`，
底色取把手色 32% 透明度。

长按与文本选择是两套互斥手势，不能同时挂在同一处：

- 普通态：气泡挂 `combinedClickable(onLongClick = 弹菜单)`，长按出
  「复制 / 复制为 Markdown / 选择文本」，**不直接复制**。
  直接复制会让想选一段话的人先被塞满剪贴板。
- 选择态：`SelectionContainer` 才包裹正文，同时**撤掉 `combinedClickable`**。
  两者共存时长按会被外层 `combinedClickable` 先吃掉，`SelectionContainer` 拿不到手势。
- 选择态必须有一个显式出口。底部操作行换成「完成选择」，否则用户选完没有回到普通态的路。
- **进入选择态的入口有两个：长按菜单与消息底部的「选择文本」按钮**，
  两者都要弹同一条轻提示，说明「完成选择」在这条消息下方。
  用户按的是「选择文本」这个字样，不一定知道退出入口在哪。

「复制」与「复制为 Markdown」必须是两种行为：「复制」走 `MarkdownPlainText.strip()`
剥掉标记，「复制为 Markdown」保留原文。两个按钮行为相同等于一个功能两个名字。

`strip()` 的边界：围栏代码块内的内容原样保留（代码里的 `*`、`_` 是语法本身），
删掉分隔线与表格分隔行时要顺手收掉相邻空行，否则会留下三连空行。

### 8.5 自行计算绘制区域时必须裁剪

凡是用 `drawImage` 或 `drawPath` 自己算 `dstOffset` / `dstSize` 的场景，
必须给容器加 `Modifier.clipToBounds()`。

原因：Compose 的绘制**默认不按布局边界裁剪**。绘制区域超出容器尺寸时，
多出来的部分会直接画到同一父容器里的其它兄弟节点之上。头像裁剪页就踩过这一点——
`drawImage` 的 `dstSize` 是「图片尺寸 × 有效缩放」，放大或图片长宽比极端时大于取景框，
溢出部分把顶部「取消 / 调整头像 / 保存」压住了。

`CropGeometry.clampOffset` 只保证图片**铺满**取景框，不保证**不溢出**，
两者是不同约束，不能靠位移计算替代裁剪。

这类问题用 `uiautomator dump` 查不出来（节点树正确，只是画错了），
只能靠截图取像素判断：沿中心列扫颜色突变点找容器边界，
再在容器外按网格采样确认没有前景像素。

### 8.6 工具交付的文件卡片

智能体调用 `send_file_to_user` 交付的文件渲染在回合末尾，独立成一个
`ChatItem.Files`，不塞进工具卡。原因：工具卡在「折叠结果」模式下整组收成一行摘要，
文件随之被折起来，而交付物恰恰是这一轮最需要看到的结果。

- 每个文件一行：44dp 缩略图、文件名、完整路径、右侧「已发送」。
- 缩略图只在图片类型存在。其它类型统一用 `Icons.Default.Description`，
  不按扩展名换图标——图标差异在 44dp 下分辨不出来，反而增加判断分支。
- 缩略图走 `ChatImageLoader` 的 data URI 分支。交付的文件字节来自工具输出里的
  base64，与历史消息里的图片共用同一套解码与 LRU 缓存，不另建加载器。
- 解码失败或格式不支持时退回到占位图标，不留空行。文件卡片本身仍然可读：
  文件名与路径才是这一行的主体。
- 图片行可点开 `ImageViewerDialog`，非图片行不可点。点不开的行不应有按压反馈。

同一回合里重复交付同一路径只显示一次，按路径去重；这一轮的卡片锚在
第一个携带交付的工具消息上，保证卡片与「处理完成 · N 步」的顺序稳定。

---

## 九、顶栏状态、浮层与选项对话框

### 9.1 顶栏状态必须反映真实连接

顶栏状态行同时承载两件事：**服务端是否在生成回复**，以及**现在能不能连上**。
这两件事必须分开渲染，不能用一个「已连接」糊过去。

| 状态 | 文案 | 色点 |
| --- | --- | --- |
| 正在生成 | 「正在生成」 | 主色偏黄 |
| `LinkState.OFFLINE` | 「连接中断，点击重连」 | 错误色 |
| `LinkState.UNKNOWN` | 「正在连接…」 | `onSurfaceVariant` |
| 已连接 | `connectedLabel(boundUrl, boundLabel)` | secondary |

`UNKNOWN` **不能当已连接**。它是「还没被证明」的状态，冷启动与网络刚恢复时都会出现。
此前它落进 `else` 分支显示「已连接」，断网后因为没有任何请求立刻失败，
状态会一直停在上一轮的 ONLINE，与眼前事实相反。

`connectedLabel()` 的措辞规则：有别名且与地址不同 → 「已通过「别名」连接至「服务器名称」」；
别名与地址相同或别名为空 → 直接用地址，避免「「192.168.1.10:8088」」双重引号；
没有服务器名称时末尾写产品名 `QwenPaw`。

**末尾的归属名不能直接用 `InstanceConfig.name`。** 名称是表单留空时由
`InstanceDraft.resolvedName()` 回落出来的首条地址，直接拿来会显示成
「已通过「EasyTier」连接至「10.0.2.9:8088」」——地址出现两次，且那不是正在用的那条。
正确做法是用 `InstanceConfig.serverDisplayName`：它拿实例的**全部**地址比对
（忽略协议前缀与结尾斜杠），命中任一条就返回空串表示「没起名字」。

排版细节：「连接至」后面接中文引号不留空格，接拉丁产品名留一个空格。
两者都写成空格会得到「连接至 「家里的机器」」，多出一个洞。

断网信号必须真的能传到聊天页。`AppViewModel` 的网络订阅里，
`if (!online) return@collect` 这类「先返回再处理」的写法会让断网分支永远空转。

### 9.2 「跳到最新」的可见条件

浮层按钮的语义是「跳到最新」，所以可见条件必须是**不在底部**。

- `visible = { !atBottom.value }`，写成 `atBottom.value` 正好相反：
  停在底部时常驻、上翻历史时反而消失。
- 判定容差用 `8.dp.toPx()`，不要写裸像素。24 像素在 xxhdpi 上只有 8dp，
  手指一碰就误判为离开底部，按钮会闪。
- 浮层自己读 `atBottom`，读发生在浮层内部，翻转时只重组这一小块，
  不会把整张消息列表连同 Markdown 正文一起拖下水。

### 9.3 「贴底」必须传偏移量，且跟随判定不能用实时位置

三个约束叠在一起才能让长回复也稳定停在底部。

**一，`scrollToItem(index)` 的默认偏移 0 是「顶部对齐」。** 它把第 index 条的
**顶部**对齐到视口顶部。最后一条经常是几屏长的回复，那样只停在它的开头，
`atBottom` 仍为 false。贴底要写 `requestScrollToItem(index, Int.MAX_VALUE)`：
偏移超出内容范围会被钳到真实底部，等价于「滚到最后」。

`requestScrollToItem` 与 `scrollToItem` 的区别：前者只登记目标下标、由下一帧按新结构应用；
后者是挂起函数、按调用那一刻的结构计算，列表正在被整体替换时落点会偏。

**二，跟随与否不能用实时 `atBottom`。** 流式内容变长会重新分组，`ChatItem` 的 key
跟着变，LazyColumn 找不到原锚点、位置跳一下；跳完 `atBottom` 变 false，
若据此刻判断就永久停止跟随。改为记录 `followTail`，只在**用户拖动结束时**采样。
`isScrollInProgress` 不能用来识别用户——它对程序化滚动同样为 true，
而贴底几乎每帧都在做。用 `interactionSource.interactions` 里的 `DragInteraction`，
并等惯性也停下（`isScrollInProgress` 转 false）再读最终位置。

**三，触发贴底的变化不止一种。** 回复结束时 `reconcileWithServer` 用服务端历史整体替换：
本地 id（`local-user-N` / 流式 `msg_id`）全换成服务端 id 而条数可能不变；
同时流式标记被清掉，一串「正文 + 思考 + 工具」被折成一个 `Steps`，条目数反而减少。
因此要挂三个键：最后一条的 id、最后一条的文本长度、分组后的条目数。

**补页也要让路。** 列表被整体替换的瞬间旧锚点消失，`firstVisibleItemIndex` 可能短暂塌到 0，
被误判成「翻到顶了」而把几个月前的历史铺回来。补页副作用开头加
`if (followTail || running) return`。

### 9.4 「正在生成」等待行

首帧到达前界面上只有用户气泡。这一段时间可能持续数秒（尤其是在没有当前会话、
还要先 `createChat` 的情况下），缺少反馈会让用户以为发送失败。

判据是 `running && messages.lastOrNull()?.role == USER`，抽成纯函数
`needsAwaitingPlaceholder(messages, running)`。

不要写成「没有任何 `streaming` 的智能体消息」：`ResponseStatus=completed` 会先清掉流式标记，
而 `running` 要到流处理结束才复位，中间这段空档会让等待行重新冒出来又立刻消失。
按「最后一条是不是用户消息」判断，任何智能体消息（正文、思考、工具、错误）落下来就永久退场。

文案取「正在生成」，不用「已发送」：此刻请求未必已经到达服务端，写成状态确认是错的。

### 9.5 二级选项一律居中对话框

聊天页需要选一项的操作（切换模型、审批模式、移动到分组）统一走 `OptionDialog`：
`Dialog` + 圆角 16dp 卡片 + `LazyColumn(heightIn(max = 420.dp))`，
选中项右侧对勾，`leading` 放固定首项（如「跟随智能体默认」「未分组」）。

不用 `DropdownMenu` 的理由：这些列表项较长（模型名形如
`CodeArts/deepseek-v4.1-flash-1017`），下拉菜单宽度会跟着内容涨，
在窄屏上挤成一列；居中对话框有稳定的最大宽度与限高。

例外：思考模式保留 `DropdownMenu`。它最多五六项，短标签，下拉反而更快。

输入框上方的模型胶囊必须是可点的（`.clickable(role = Role.Button)`），
点它打开与「...」菜单里同一个对话框——胶囊显示当前模型，点它换模型是最直觉的路径。