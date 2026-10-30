# 架构说明

本文面向需要修改本仓库代码的开发者，说明分层结构、目录职责、状态如何在各层之间流动，
以及若干不直观的工程决定的理由。

如果想先把项目跑起来，请见 [README](../README.md)；服务端接口形状与已知失败模式见
[protocol.md](protocol.md)；界面层的约定与性能测量口径见 [ui-guidelines.md](ui-guidelines.md)。

---

## 一、整体分层

```
MainActivity ──> AppViewModel ──> InstanceRepository ──> InstanceStore + QwenPawApi
     │                                                            │
     └──> ChatViewModel ──> ChatStreamClient ──> SseReader ──> ChatEventParser
                  │                                                  │
                  │                                    StreamAccumulator
                  │                                                  │
                  └──> AppDatabase (Room: drafts / cached_messages) ─┘
```

依赖采用**手工装配**，没有引入依赖注入框架。所有单例在 `QwenPawApp.onCreate` 中一次性创建
（`QwenPawApi`、`ChatStreamClient`、`AppDatabase`、`NetworkMonitor`、`AvatarStore`、
`InstanceRepository`、`FontManager`），再由 `MainActivity` 传给各个 `ViewModel`。

选择手工装配而非 Hilt 的理由是本项目的依赖图规模很小：需要注入的对象不足十个，
层次也只有两层。引入注解处理器会同时增加编译期开销与调试时的间接性，
而这个规模的项目用构造函数传参已经足够清晰。

---

## 二、目录职责

| 路径 | 职责 | 什么时候该改这里 |
| --- | --- | --- |
| `data/local/` | Room 数据库（草稿、离线消息缓存）与 `InstanceStore`（实例配置与 token 的加密存储） | 需要新增本地持久化字段，或改动凭据存储方式时 |
| `data/model/` | 服务端 DTO，使用 `kotlinx.serialization` | 服务端字段增删，或新增接口需要新模型时 |
| `data/remote/` | `QwenPawApi`（全部 HTTP 端点）、`ChatStreamClient`（SSE 连接） | 接入新端点，或调整请求头、超时、重试策略时 |
| `data/stream/` | SSE 解析链路：`SseReader` → `ChatEventParser` → `StreamAccumulator`；`HistoryMapper` 负责把历史接口的返回映射为渲染单元 | 服务端帧格式变化，或发现某类内容没有被正确归类时 |
| `data/`（根） | `NetworkMonitor`（网络类型与连通性）、`InstanceRepository`（多实例与地址解析）、`AddressFallback`（多地址回退判定） | 改动地址优先级、回退策略或网络感知时 |
| `avatar/` | `AvatarStore`（头像文件）、`AvatarIntake`（GIF 判定与 EXIF 纠正）、`CropGeometry`（裁剪换算）、`AvatarCropScreen`（裁剪页） | 调整头像规格、裁剪交互或图片处理方式时 |
| `log/` | `RunLog`（进程内环形缓冲）、`LoggingInterceptor`、`LogImporter` | 新增日志类别、改动导出格式或脱敏规则时 |
| `ui/chat/` | 对话页、会话列表与分组、输入条、Markdown 渲染、工具卡片、消息气泡与头像、语音浮层 | 绝大多数界面改动的落点 |
| `ui/settings/` | 实例连接表单、地址管理、设置页、智能体选择 | 设置项增删，或连接流程变化时 |
| `ui/theme/` | 配色、字号缩放、MiSans 字体接入 | 调整视觉 token 或字号体系时 |
| `ui/log/` | 运行日志页 | 调整日志展示形态时 |
| `voice/` | sherpa-onnx 流式识别、模型下载解压、重采样 | 更换识别引擎或调整采集参数时 |
| `font/` | MiSans 运行时下载与校验 | 调整字体下载策略时 |
| `notify/` | 本地通知。项目没有推送通道，只在前台丢失时提醒 | 调整通知触发条件时 |

---

## 三、状态如何流动

### 3.1 发送一条消息

1. `ChatViewModel.send()` 首先检查 `_state.value.running`。若为真，直接拦截，不再发起请求。
   服务端在这种情况下会返回 409，提前拦截可以省掉一次无效往返，也能在离线状态下立即给出反馈。

2. `StreamAccumulator.appendUser()` 把用户气泡立即塞进消息列表，实现本地乐观渲染。
   这一步是必要的：若等待服务端确认再显示，用户在弱网下会感到输入后毫无反应。

3. `ChatStreamClient` 建立 SSE 连接，将逐帧数据交给 `ChatEventParser`。

4. 增量帧只携带 `msg_id`、`index` 与 `text`，**不携带内容类型**。
   类型由先前到达的 `object=message` 帧声明，`StreamAccumulator` 据此维护
   `msg_id → Kind` 的映射表，后续增量按 `msg_id` 查表。

5. 每收到一批事件，执行
   `_state.update { it.copy(messages = accumulator.snapshot()) }`，
   Compose 侧的 `collectAsState` 随之触发重组。

### 3.2 `snapshot()` 必须是新列表

`StreamAccumulator.snapshot()` 的实现是 `messages.toList()`，也就是每次推送都生成一个新列表。

这一条不是风格选择，而是正确性要求。Compose 判断状态是否变化依赖引用比较：
若换成原地修改同一个可变列表并直接返回它，引用始终不变，
`collectAsState` 不会认为状态发生了变化，界面就不会刷新。
更糟的是 `LazyColumn` 也会认为数据未变，从而跳过本应重算的条目。

代价是每次推送都会分配一个新列表。在本项目的消息量级下，这个分配开销远小于「界面不刷新」的风险。

### 3.3 断线与停止

- **客户端断开不影响服务端。** 重新进入会话时，用 `reconnect: true` 再发一次
  `input` 为空的请求，即可挂回仍在运行的流。
- **停止之后必须重新拉取历史。** `stop` 之后服务端保留了已生成的部分内容，
  若只把本地状态改为「已停止」，那些已落库的内容会在界面上消失，
  下次进入会话时又突然出现。
- **取消协程时的收尾位置。** 取消承载流的 `Job` 会抛出 `CancellationException`，
  取消点之后的常规收尾代码不会执行，于是 `running = false` 永远不生效，
  按钮会一直卡在「停止」。正确做法是把收尾逻辑放到新启动的协程里执行。

### 3.4 渲染单元

原始 `ChatMessage` 不直接进入列表。它先经过 `ui/chat/ChatTimeline.kt` 折叠成 `ChatItem`：

- 按用户消息切分**回合**，每个智能体回合再按 `AssistantCollapse` 的三档设置折叠；
- 连续的「中间正文 / 思考 / 工具调用」合并为一个 `ChatItem.Steps`；
- 折叠逻辑是纯函数，不依赖 Compose，因此可由 `ChatTimelineTest` 在 JVM 上覆盖。

`countSteps()` 只统计思考与工具调用。中间正文虽然也折进同一组，但不计入步骤数，
这与 QwenPaw 官方 Console 的口径一致：标题里的数字表示「做了几件事」，而不是「几条消息」。

`ChatScreen` 随后按**条目**开窗渲染（`buildTimeline`），首屏只铺最近一页，向上滚动时补页。

窗口必须按**分组后的条目**计算，而不是按原始消息数。原因是两者量级差异很大：
一个包含多轮工具调用的「处理过程」条目，可能吞掉几十条原始消息。
若按消息数分页，翻一页可能只得到一两个条目，填不满屏幕，
用户会感觉「滚一下就到底了」而反复触发补页。

### 3.5 两份消息列表：历史前缀与本轮增量

界面上的消息始终是「历史 + 本轮增量」，两者**分开存放**：

| 变量 | 内容 | 更新时机 |
| --- | --- | --- |
| `ChatViewModel.historyPrefix` | 本轮之前已有、只读的消息 | `openChat` 成功、离线读缓存、`reconcileWithServer` |
| `StreamAccumulator` | 从打开会话那一刻起新增的消息 | 逐帧追加 |
| `ChatUiState.messages` | 两者的拼接结果 | 由 `mergedMessages()` 统一产出 |

拼接规则是纯函数 `mergeHistory(history, live)`，由 `MergeHistoryTest` 覆盖。
它不做去重：两侧的 id 体系不同（服务端 id vs `local-user-N`），强行合并会吞掉合法内容。
真正的对齐在回复结束后由 `reconcileWithServer` 用服务端历史整体替换一次。

历史上这两份列表混在一起写：`startRun()` 与 `pushSnapshot()` 直接写
`messages = accumulator.snapshot()`，而 accumulator 在 `openChat` 时刚被清空。
于是每发一条消息，此前所有消息都被整屏冲掉，要等流式内容陆续到达才长回来。
这就是「发送后页面空荡荡」与「回复完毕前看不到历史」的同一个根因。

**规则**：同一份界面状态有两个写入路径时，只要其中一条是「整体覆盖」，
另一条迟早会把对方的数据吃掉。覆盖必须收敛到一个拼接函数里。
换实例、删会话、归档当前会话、批量删除/归档时，要把前缀一起清空，
否则旧服务器的残留会被拼回来。

### 3.6 `bind()` 只在真的换了实例时清空会话

`ChatViewModel.bind(instanceId)` 会被四个地方调用：切换实例、顶栏重连、
网络恢复、改动地址。它内部是否清空 `currentChatId`，决定了下一条消息是发进
当前会话还是新建一个。

历史上这里无条件清空。于是一次同实例重连（例如顶栏点一下重连）就把当前会话置空，
`send()` 随后走新建分支——用户看到的现象是「明明选了会话，发出去却新建了一个」。

判定抽成纯函数 `shouldClearOnBind(current, next)`：**两者都非空白且不同**才清空。
`next` 为空（例如冷启动尚无实例）时不清，`current` 为空时也无需清。
这条规则由 `ChatViewModelBindTest` 覆盖。

`refreshAll()` 复用 `bind()` 是合理的——它本来就是「重新绑定当前实例」；
问题不在复用，在于 `bind()` 把「重新绑定」误解成了「换实例」。

---

## 四、本地持久化

本地只有两张表，结构都很轻：

- `drafts`：每个 `(实例, 会话)` 组合存一条草稿，写入带 400ms 防抖。
  防抖是为了避免每次按键都写磁盘，同时保证用户切走时草稿不丢。
- `cached_messages`：用于离线查看历史。

**服务端自己保存会话上下文**，客户端发消息时只传 `session_id`，不传历史。
因此本地不需要消息同步、冲突合并、增量对账这一整套机制。
这也是本项目能保持较小体量的主要原因之一。

---

## 五、若干不直观的工程决定

| 决定 | 原因与不这么做时的代价 |
| --- | --- |
| 不引入 Coil 处理会话图片，自行实现 `ChatImageLoader` | 会话图片只需要支持三种地址形态（data URI、HTTP、服务端本地路径）。通用图片库覆盖这三种形态仍要写自定义逻辑，而它自带的内存缓存与这里的 LRU 会重复。头像为什么又用了 Coil、以及两者的边界，见下一条。 |
| 头像用 Coil，会话图片不用 | 头像可能是 GIF，逐帧解码需要 `AnimatedImageDrawable` 或 `Movie` 这类实现；自写要分别处理 API 28+ 与 26–27 两条路径，还得管动画的生命周期。Coil 的 `coil-gif` 已经把这套封装好，且它只服务 `filesDir` 下的本地文件，不碰 `ChatImageLoader` 的三种地址形态，两套体系没有重叠。代价是新增约 700KB 依赖与一次 R8 复验。静态头像也走同一条路径，没有第二条渲染分支。 |
| 不引入 Markdown 库，自行实现 `MarkdownParser` | 只需要覆盖段落、标题、列表、引用、代码块与表格。重型库在流式刷新场景下反而更慢：流式输出期间正文每帧都可能变化，通用解析器的完整语法树重建开销明显高于按需的轻量解析。 |
| SSE 自行解析，不用 OkHttp 的 `EventSources` | 服务端的帧格式与标准 SSE 事件并不完全一致，需要按 `object` 与 `type` 两个维度分流。`EventSources` 的事件模型容不下这种双维度结构，强行套用会把类型判断散落到各个回调里。 |
| 没有前台服务，也没有推送 | QwenPaw 不提供推送通道。引入前台服务只能延长进程存活时间，无法提供真正的后台提醒，却要付出常驻通知与耗电的代价。这是一个明确的取舍，不是遗漏。 |
| `HistoryMapper.uniqueIds` 为重复 id 追加序号 | 历史里同一个 `msg_id` 会出现多次，直接用作 `LazyColumn` 的 key 会抛出 `IllegalArgumentException: Key ... was already used` 并崩溃。 |
| 日志只进内存环形缓冲，不写文件 | 用户要的是「本次运行的日志」。写文件会引入清理、配额、权限与并发写等无关复杂度，而导出功能已经能满足「把现场发给别人」。 |
| 裁剪几何抽成纯函数 `CropGeometry` | 手势拖动与缩放最容易出「拖到底还能拖」「裁剪区偏出图片」这类问题，在 Composable 里没法断言；抽成纯函数后可被 JVM 单测覆盖边界。 |

---

## 六、地址解析与网络感知

同一台服务端常有多个入口。局域网地址在家快、出门不通；EasyTier 之类的隧道地址相反。
用户不应为此手动切换实例，因此地址列表带优先级，请求时按顺序回退。

### 6.1 地址模型

`InstanceConfig.addresses: List<AddressEntry>`，每条含 `url`、可选的 `alias`（显示名）
与可选的 `preferOn: NetworkHint`（`kind` 为 `vpn` 或 `wifi`，wifi 可再限定 `ssid`）。

两个旧字段只作为迁移源：`baseUrl`（最早的单地址）与 `baseUrls`（上一版的字符串列表）。
两者都标了 `@EncodeDefault(NEVER)`——存储用 `encodeDefaults = true`，
不加这个标记会把 `"baseUrl": null` 一起写进去。`migrated()` 按
`addresses > baseUrls > baseUrl` 三级迁移，且幂等。

### 6.2 解析顺序

`InstanceRepository.resolveOrder()` 输出一次请求应当尝试的地址顺序，五级优先：

1. 本次运行手动指定的地址（`sessionPick`，不落盘）
2. 当前网络命中某条地址的 `preferOn`（例如「检测到 VPN 时用 EasyTier」）
3. 用户长期指定的 `preferredBaseUrl`
4. 本次运行在该网络下已探明的可用地址（`runtimeHit`）
5. 用户设定的原始顺序

**`sessionPick` 与 `preferredBaseUrl` 是两层，不能合并。** 前者只影响本次打开
（用户在设置页点「本次使用」），进程结束即失效；后者写进配置、长期有效
（在「编辑实例」里改）。把两者塞进一个字段会导致「只想临时换一下」变成永久改动，
或反过来让长期偏好重启后丢失。

五级里前四级都只把地址**提到最前**，不去重也不排他。排他会让一次手动选择
在地址故障时使实例彻底不可用——这与「失败就回退」的诉求直接冲突。

优先级判定的实现抽成纯函数 `resolveOrderPure()`，因为它有五个输入、
五种组合，只有单测能覆盖到边界（例如 `sessionPick` 指向已从列表删掉的地址时应当忽略）。

**`sessionPick` 必须同步进 `AppState`。** 仓库里它是普通 `mutableMap`，
不是可观察对象；界面直接读仓库的话，用户点完「本次使用」Compose 不会重组，
界面停在「自动选择」。`AppViewModel.chooseSessionAddress()` 因此同时写仓库与
`_state.sessionPicks`，界面读后者。

### 6.3 `runtimeHit` 必须按网络分桶

键是 `id@网络类型`。单桶缓存会造成很具体的问题：在家连上局域网后缓存记下
`192.168.x.x`，出门 VPN 上线时缓存仍指向那个地址，于是每条请求都要先等一遍
连接超时才回退——这正是「备用地址不生效」的观感来源。网络一变桶就换，缓存自然失效。

`remove(id)` 必须按 `id@` 前缀清掉该实例的全部桶，否则删除实例后缓存残留。

### 6.4 冷启动必须探测

`resolvedBaseUrl()` 返回的只是「排序后的第一个」，**不保证可达**。
冷启动或重装后运行期缓存为空，若用户把已在当前网络下失效的地址排在第一位，
界面会直接报连接失败，备用地址没有机会被尝试。

因此 `AppViewModel` 在冷启动绑定后调用 `probeReachable()`：按解析顺序用
`probeClient()`（8 秒 `callTimeout`）逐个探测 `GET /api/version`，
命中地址与当前绑定不同就重绑并重载。全部失败则保持原状，由用户手动处理。

### 6.5 网络感知

`NetworkMonitor` 注册 `registerDefaultNetworkCallback`，输出 `current: Snapshot(kind, ssid)`
与 `online: Boolean`。三个实现约束：

1. **必须同时覆写 `onAvailable`、`onLost` 与 `onCapabilitiesChanged`。**
   VPN 断开时系统只回调 `onLost`，不再给一次能力变化；只听能力变化的话，
   VPN 拔掉后客户端仍以为自己在 VPN 上。
2. **判 VPN 看 `TRANSPORT_VPN` 本身**，不能用「非 wifi 即 vpn」反推——
   VPN 生效时 `TRANSPORT_WIFI` 通常仍为 true。
3. **SSID 拿不到时返回 null**，不主动申请定位权限。不用该功能的用户不应被打扰；
   代价是无法按 WiFi 名称绑定地址，VPN 判断不受影响。

`AppViewModel` 订阅 `online`，只在 `false → true` 跳变时重载。
订阅时要 `drop(1)`：`StateFlow` 在订阅瞬间会先发一次当前值，
不丢会立刻重复拉一轮会话列表。

### 6.6 验证与探测的超时

主客户端 `defaultClient()` 的连接超时 20 秒、读超时 10 分钟，且没有 `callTimeout`。
这对正常请求合适（生成可能很久），但「连上但对端不回包」（隧道或 VPN 半开连接）
会让验证界面干等十分钟。

因此新增 `probeClient()`：connect 4s / read 5s / `callTimeout 8s` /
`retryOnConnectionFailure = false`，作为 lazy 单例复用连接池。
验证只发一个 `GET /api/version`——验证的目的是回答「这个地址现在通不通」，
不需要同时判断是否要登录，鉴权交给真正连接时的流程。

### 6.7 地址规范化：协议、端口与托管平台

所有入口（验证、连接、列智能体、保存实例）最终都调用
`InstanceRepository.normalizeBaseUrl()`，因此地址相关的三条规则集中在这一个函数里，
不在各页面重复。

**协议按主机判断，不再一律补 `http://`。** 内网地址（`localhost`、`127.*`、`::1`、
`10/8`、`172.16/12`、`192.168/16`、`169.254/16`、`100.64/10`，以及
`.local`、`.lan`、`.internal`、`.home` 结尾的域名）补 http，其余补 https。
显式写了协议的地址不改写。判据是「这个地址在内网还是公网」，
而不是「服务端是否支持 https」——后者客户端无从得知。

**端口回填跟随协议**：https 回填 443，http 回填 80。此前统一回填 80，
`https://example.com` 会变成 `https://example.com:80`，请求直接连不上。

**托管平台域名在规范化阶段拦截。** `qwenpaw.platform.agentscope.io`
（含子域名）与 `modelscope.cn`（含子域名）抛 `IllegalArgumentException`，
消息即给用户看的提示。这两类实例连接走平台自己的鉴权与转发，
按自建实例直连只会得到 401/404，在填地址这一步说明白比让用户反复试更好。

放在这里同时解决了「阻止进入下一步」：验证连接必然经过本函数，
抛错后 `InstanceWizard` 的 `verified` 保持 false，「下一步」因此不可点，
不需要在界面层再写一次域名判断。

---

## 七、测试

`app/src/test/` 下是纯 JVM 单测，覆盖解析、映射、累积与折叠这类纯逻辑。
这些测试不依赖设备，可以反复快速执行：

```bash
./gradlew :app:testDebugUnitTest
```

`app/src/androidTest/` 只有一条启动冒烟测试。

设备端测试的取舍有明确理由：启动成本高，一次运行要经历安装、启动、等待界面稳定；
失败信息也差，报错往往只给出 instrumentation 层面的笼统结论。
因此能覆盖的逻辑一律写成 JVM 单测，设备端只保留最小集的冒烟验证。
这条约定在本仓库是硬性的，详见 [AGENTS.md](../AGENTS.md)。

---

## 八、UI 层约定

以下三条是跨页面通用的约束，详细说明与反例见 [ui-guidelines.md](ui-guidelines.md)。

- **边到边适配。** `targetSdk = 35` 起，Android 15 对应用强制全屏，
  系统栏透明浮在内容之上。自定义顶栏与底栏必须自行处理系统栏 inset。
- **不要把 `derivedStateOf` 用 `by` 委托在页面组合作用域中读取。**
  它的状态翻转会重组整个作用域。正确做法是保留 `State` 对象，
  在真正需要的位置显式读 `.value`，或把读取点封装进更小的组件内部。
- **列表项中的昂贵解析必须 `remember` 缓存。** 条目滚出视口会被销毁，
  滚回来时需要重新执行解析；不缓存就会在滚动过程中持续产生主线程开销。