# 开发 QwenPaw Android 客户端

> **Workspace:** /home/csdetr/Documents/trae_projects/trae

---

**User**

openclaw有一个很好用的安卓客户端，可以连接到openclaw后，与智能体对话并对gateway进行管理，有大佬给qwenpaw做这样的项目吗？如果没有，开发一个"该有的都有"的"QwenPaw Node"难度怎么样呢？
`https://openclaw.ai/`
`https://qwenpaw.agentscope.io/`
`https://github.com/openclaw/openclaw`
`https://github.com/agentscope-ai/QwenPaw`
`https://docs.openclaw.ai/zh-CN/platforms/android`

**Assistant**

我会查看这两个项目，了解现有情况，以及构建一个 QwenPaw Android 客户端的难度如何。

Key fact already: QwenPaw 官方只有 Console / TUI / Tauri 桌面端，**没有 Android 客户端**。我需要再确认两件事：QwenPaw 的 API 是否适合做移动端，以及 OpenClaw Android 端的工程规模。

我先确认两件事：QwenPaw 是否已有社区 Android 客户端，以及它的 API 是否覆盖网关管理。

# 结论先说

**目前没有。** 我没找到任何第三方给 QwenPaw 做的安卓客户端（搜索到的“Qwen App”是阿里通义的消费者客户端，和自托管的 QwenPaw 无关；Gitee 上的 QwenPaw 镜像也只是镜像）。

而且 QwenPaw 官方自己也没有 Android 端：端侧只有 **Console(Web) / TUI / Tauri 桌面端(Windows+macOS Beta)**。所以这个位置是空的。

难度上，**"该有的都有"大致是一个中大型独立项目**，但它比 OpenClaw 的安卓端**简单一个量级**——原因是两边架构根本不同。

---

## 一、为什么 QwenPaw 版比 OpenClaw 版容易得多

| 维度 | OpenClaw Android | QwenPaw Node（假设） |
|---|---|---|
| 服务端协议 | 自研 Gateway WS 协议（节点+控制平面，双向双向事件） | **纯 REST + SSE**，HTTP POST `/api/console/chat` |
| 配对 | QR/setup code + `role:node` 设备配对 + CLI 审批 + TLS 固定 | JWT 登录（`/api/auth/login`），Bearer token |
| 手机是不是"节点" | 是。相机、位置、屏幕、通知、SMS、Talk 全在协议里 | 否。QwenPaw **没有** node / device capability 概念 |
| 语音 | Talk 模式、实时中继、Wear OS 音频转发 | 无（最多接系统语音识别填输入框） |
| 离线可靠投递 | 按 Gateway 分桶的 outbox + 幂等键 + 冲突恢复 | 无，要自己做 |
| 工程规模 | Kotlin + Compose 的成熟大仓，还有 Wear、benchmark、截图流水线 | 从零开始，但只对接一套 HTTP API |

关键差异：**OpenClaw 的安卓端是把手机变成网关的一个"器官"；QwenPaw 的架构里手机只能当一个"远程遥控器 + 聊天窗"**。少了整套节点/设备能力协商，工作量直接砍掉一半以上。

---

## 二、"该有的都有"清单——分三层看

**L1 必做（能用的底线，工作量小）**
- 连接管理：填 BaseURL + 登录拿 JWT（支持永久 token / `expires_in`），token 加密存（EncryptedSharedPreferences 或 Keystore）
- 聊天：`POST /api/console/chat`，解析 SSE，增量渲染 + 中断
- 会话：`session_id` / `user_id` 管理、历史列表、新建
- 多 Agent：切 `X-Agent-Id`
- 前端形态：Compose + Material 3，Markdown 渲染（流式）、代码块复制

因为 QwenPaw 服务端**自己存上下文**（只传 `session_id`，不传历史），客户端逻辑非常轻，不用做消息重组、上下文裁剪这些麻烦事。

**L2 "该有的都有"（体验完整，工作量中）**
- 多实例/多网关切换，每个实例独立凭据与缓存
- 前台服务保持长连接/流式会话，通知栏显示"正在运行"
- 离线草稿 + 发送队列（QwenPaw 没提供 outbox，得纯客户端实现，参考 OpenClaw 的做法）
- 文件/图片附件、剪贴板粘贴长文本
- 流式 Markdown / 代码高亮 / 表格 / Mermaid（OpenClaw 有独立的 `packages/mermaid-renderer`，可借鉴思路但要自己接）
- 工具调用可视化：QwenPaw SSE 里有 tool 相关输出，需要按 `content.type` 做卡片化渲染，**这块需要读源码对齐字段，文档只写了 text**
- 深色模式、字号、国际化

**L3 "管理 Gateway"（真正拉高难度的地方）**
- 模型配置（Settings → Models）、Provider/API Key（涉及密钥，必须严格不落日志、不落明文）
- Skills / Plugins / MCP 的启停与策略（Access Policy 里 allow/deny/ask）
- **审批流**：Tool Guard 和 Access Policy 的 `ask` 会在 Console 弹审批卡片——移动端要实现这个，就意味着**不只是拉流式回复，还要有第二个通道接收"待审批"通知并回传决策**。而 QwenPaw 官方文档的 REST API 部分只覆盖了 chat，这部分接口需要读源码确认
- Cron / 定时任务、Channel 状态、备份触发
- 安全层开关（Tool Guard / File Guard / Skill Scanner / Sandbox）
- 监控：`/api/version` 等轻量探活

L3 是"该有的都有"的真正成本所在：**聊天是 QwenPaw 少数被正式文档化的接口，管理面大部分没有稳定 API 契约**。

---

## 三、难度评估（不报时间，只说量级）

- **L1 单机聊天客户端**：一个熟练的 Kotlin 开发者，独立可完成。没有协议逆向，纯 HTTP/SSE，是稳妥的入门切片。
- **L1+L2 完整体验**：需要认真的工程投入，主要是 SSE 流式渲染、Markdown 性能、离线队列、前台服务生命周期——这些是"难在细节不在概念"的活。
- **L3 完整管理端**：难度跃升。因为 QwenPaw 的 Console 前端是 React/TSX（仓库约 15% 代码是前端），**管理功能的事实契约在 Console 的代码里，而不在 API 文档里**。你需要读 `src/qwenpaw` 的路由 + `console/` 的调用，才能知道有哪些接口、字段是什么。这部分还容易随 QwenPaw 版本漂移。
- **审批流**：算是最不确定的一块。如果 QwenPaw 没有现成的"待审批事件"推送机制，移动端就做不到及时审批，只能退化成"轮询"或"打开 Console 网页"。

所以大致排序：

> 做一个能聊天的 QwenPaw 安卓客户端 = **容易偏中**；
> 做到 OpenClaw 安卓端那种程度 = **难，但不完全在客户端这一侧**。

因为 OpenClaw 之所以能做到那么多，是因为**网关协议本身就是为多端、节点、能力协商设计的**。QwenPaw 目前是"单机 + 多渠道推送（钉钉/飞书/微信）"的思路，手机通过 IM 就能用——它的产品哲学里并没有"手机作为一等公民客户端"这个位置。

---

## 四、现实建议

如果目标是"QwenPaw Node 该有的都有"，建议按这个顺序切：

1. **先做 L1，并严格只用官方文档化的 API**（`/api/console/chat`、`/api/auth/*`、`/api/version`）。这段可以完全照文档写，不依赖源码。做成开源 APK 发 Release，先占住"QwenPaw 第一个安卓客户端"这个生态位。
2. **L2 再补**。附件、离线队列、SSE 渲染稳定化。这时候值得去 QwenPaw 仓库提 Issue/Discussion，问官方有没有计划把 Console 的管理面整理成正式 API——有官方背书，L3 才不会是"跟着前端代码追版本"。
3. **L3 谨慎**。如果官方没有稳定管理 API，更聪明的做法是 **WebView 内嵌 Console**（用登录 token 注入）+ 原生壳负责聊天和通知，而不是把每个设置页都用 Compose 重写一遍。OpenClaw 的安卓端很多设置页其实也是靠 Gateway 的能力协商在收敛，不是原生全量重写。
4. **慎入 SDK 深水区**：QwenPaw 的 Sandbox 在 Android 上不可用（Seatbelt/bwrap/Landlock/AppContainer 都不覆盖 Android），这意味着**手机上不可能跑 QwenPaw 本体**，只能是远程客户端。这一点要先想清楚——它决定了这个项目永远是 companion，不是 standalone。

---

## 一句话回答

**没有大佬做过，位置是空的；做一个"能聊天 + 能管"的 QwenPaw 安卓客户端，难度中等偏上，但比复刻 OpenClaw 安卓端低一个量级。** 真正的分水岭不在 Android 端，而在 QwenPaw 官方愿不愿意把管理面稳定成 API——否则"该有的都有"就会变成"跟着 Console 前端代码追版本"。

如果你愿意，我可以直接读 QwenPaw 源码，把 `/api/*` 实际暴露的路由、SSE 事件类型、以及审批流有没有推送通道整理成一份准确的能力清单，再据此定 L1/L2/L3 的切分。

Sources:
- [OpenClaw Android 文档](https://docs.openclaw.ai/zh-CN/platforms/android)
- [OpenClaw apps/android README](https://github.com/openclaw/openclaw/tree/main/apps/android)
- [QwenPaw README](https://github.com/agentscope-ai/QwenPaw)
- [QwenPaw REST API 文档](https://qwenpaw.agentscope.io/docs/api-tutorial)
- [QwenPaw 安全文档](https://qwenpaw.agentscope.io/docs/security)

---

## 五、实测协议记录（QwenPaw 2.2.1）

以下内容全部来自对本机实例（`--host 0.0.0.0`，`:8088`）的实际抓包与设备联调，不是文档推演。客户端实现（`app/src/main/java/io/qwenpaw/node/`）以本节为准。

### 5.1 鉴权

- `GET /api/auth/status` → `{"enabled":false,"has_users":false}`。`enabled=false` 时所有接口直接可用，不需要 token。
- 开启鉴权后走 `POST /api/auth/login` 拿 token，后续请求带 `Authorization: Bearer <token>`。
- 客户端把 token 存进 `EncryptedSharedPreferences`，Keystore 不可用时降级普通 prefs。

### 5.2 会话

| 接口 | 说明 |
| --- | --- |
| `GET /api/version` | `{"version":"2.2.1"}` |
| `GET /api/agents` | 智能体列表，连接时用来校验 `agent_id` 是否存在 |
| `GET /api/chats` | 会话列表，返回 `status`（`idle` / `running`）与 `updated_at` |
| `POST /api/chats` | 建会话，需 `name`、`session_id`、`user_id`；`session_id` 是真正进 SSE 的那个 id |
| `GET /api/chats/{id}` | 历史，`{"messages":[...],"status":"..."}` |
| `GET /api/chats/{id}/status` | 只有 `{"status":"idle"}`，比拉整个历史轻 |
| `PATCH` / `DELETE /api/chats/{id}` | 重命名 / 删除 |

**注意两点**：
1. 列表顺序不保证。运行中的会话可能沉底，客户端需自己按 `status=="running"` 优先 + `updated_at` 倒序排。
2. `session_id` 与 `chat id` 可能不同（客户端建会话时传 `mobile-xxxxxxxx`），发消息要用 `session_id`。

### 5.3 SSE 流

`POST /api/console/chat`，body 形如：

```json
{
  "session_id": "mobile-xxxxxxxx",
  "user_id": "mobile-user",
  "input": [{"role":"user","content":[{"type":"text","text":"你好"}]}],
  "reconnect": false
}
```

实测帧类型（按 `object` 字段区分）：

- `object=response`：`status` 为 `created` / `in_progress` / `completed` / `failed`，是整轮的生命周期。
- `object=message`：`type` 为 `message` / `reasoning` / `plugin_call` / `plugin_call_output`，声明一条消息及其类型。
- `object=content` 且 `delta=true`：增量文本，带 `msg_id`、`index`、`text`。**增量帧本身不带类型**，必须靠前面的 `object=message` 建立 `msg_id → 类型` 映射。
- `type=turn_usage`：本轮 token 用量，`completed` 之后还会补一帧。
- `type=rate_limited`：限流。**这一帧自身带 `error` 字段**，解析时必须在通用 `error` 分支之前判断，否则会被误判成失败终态。
- `type=heartbeat`：保活，忽略即可。

工具调用数据在 `content[].data` 里：`plugin_call` 是 `{call_id,name,arguments}`，`plugin_call_output` 是 `{call_id,name,output}`，两者按 `call_id` 配对成一张卡片。

**历史接口的坑**：同一个 `msg_id` 会在历史里出现多次（reasoning 与正文共用 id，或一条 assistant 消息被拆成多条记录）。直接拿来当 `LazyColumn` 的 key 会抛 `IllegalArgumentException: Key ... was already used` 并崩溃。客户端在 `HistoryMapper.uniqueIds` 里对重复 id 追加序号。

### 5.4 断线与停止

- 客户端断开**不影响**服务端继续执行；重新进入会话时用 `reconnect: true` 再发一次空 `input` 即可挂回运行中的流。
- `POST /api/console/chat/stop?chat_id=<chat id>` → `{"stopped":true}`。停止后服务端保留用户消息与已生成的部分内容，因此客户端在停止后**必须重新拉一次历史**再渲染，否则会丢掉已落库的部分。
- 取消本地流 Job 会抛 `CancellationException`，收尾逻辑要放到新协程里，否则 `running=false` 那步永远不执行，按钮会卡在「停止」。

### 5.5 并发与错误

- 同一 chat 并发发送 → `409 {"detail":"A task is already running for this chat. Wait for it to finish or use a different session_id."}`。客户端在 `running` 状态下直接拦截，不依赖服务端返回。
- 连接失败时 OkHttp 的异常文案不统一：可能是 `Failed to connect to /10.0.2.2:8088`（拒绝），也可能是 `failed to connect to /10.0.2.2 (port 8088) from /10.0.2.15 (port x) after 20000ms`（丢包超时，且不带 `ConnectException`）。只按异常类型判断会漏，必须再匹配文案。提示语统一成中文。

### 5.6 审批

- `GET /api/approval/list` → `{"pending_approvals":[...],"count":n}`，列表项含 `requestId`、`sessionId`、工具名与摘要。
- `POST /api/approval/approve` / `deny`。
- **没有推送通道**。Console 自己也是每 2.5s 轮询 `GET /api/console/push-messages`，客户端同样用 2.5s 轮询审批列表。
- 审批级别在 `~/.qwenpaw/workspaces/default/agent.json` 的 `approval_level`，改成 `ASK` 才会对高危命令弹审批（联调时改过，测完要还原成 `AUTO`）。

### 5.7 附件

- `POST /api/console/upload`（multipart）返回 `{"url":"/home/<user>/.qwenpaw/workspaces/default/media/..."}`，是**服务器本地绝对路径**。
- 请求侧要放成扁平字段 `{"type":"image","image_url":"..."}`（详见 5.11）；
  默认字段用 `source.url` 会被服务端静默丢弃。
- 实测：上传 64x64 蓝色 PNG，模型调用 `view_image` 后正确答出 RGB(74,107,181)。

### 5.8 通知

QwenPaw 没有推送通道，所以客户端只做**本地**通知：进程里拿到 `completed` 时，若 App 已不在前台（`AppVisibility.visibleCount == 0`），就发一条「回复已完成」通知。App 在前台时不发，避免打扰。这也意味着 App 进程被系统杀掉后不会再有提醒——这是放弃实时推送的必然代价，草稿与历史仍从服务端补齐。

### 5.9 已知取舍

- **不做**离线自动补发：断网时保留草稿与失败气泡，由用户点「重试」。
- **不做**实时推送/常驻后台长连接：`ChatWatchService` 只是前台服务壳，不做真正的保活。
- **不做** Mermaid 渲染：代码块降级为可复制源码。
- **不做** L3 管理面（智能体配置、技能管理、日志查看），需要管理 API 稳定后再补。

### 5.10 语音输入（离线）

语音输入全程在手机本地完成，不依赖系统语音服务，也不上传音频。Android 自带的
`SpeechRecognizer` 需要设备安装识别服务，实测这台真机没有，所以改为自带离线引擎。

- 引擎：[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)（k2-fsa，Apache-2.0）。
  文档 <https://k2-fsa.github.io/sherpa/onnx/>，Android AAR 从
  [Releases](https://github.com/k2-fsa/sherpa-onnx/releases) 取，仓库里放的是
  `app/libs/sherpa-onnx-1.13.3.aar`。
- 模型：[sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models)，
  中英流式 zipformer2，int8 量化，压缩包 126MB，解压后约 168MB。由用户
  在设置里手动下载，下载地址写死在 `SpeechModelManager.MODEL_URL`。
- 解压：`tar.bz2` 用 [commons-compress](https://commons.apache.org/proper/commons-compress/)
  的 `BZip2CompressorInputStream` 边解边喂给 `TarArchiveInputStream`，不落中间文件。
- 采集：`AudioRecord` 16kHz 单声道，每 0.1 秒送一块给识别器。sherpa 会根据每块
  采样数反推采样率，块长不固定会报 `You changed the input sampling rate` 直接崩，
  所以必须固定块长；设备实际采样率不同时先用 `LinearResampler` 折回 16kHz。
- 收尾：点「说完了」只调 `AudioRecord.stop()` 并置停止标志，让采集协程自己走
  `inputFinished()`，不要直接取消协程——取消会跳过收尾并把状态卡在录音中。
- 入口开关：设置里的「显示语音按钮」。开关打开但模型没下载时会弹说明，
  直接引导去下载；模型没就绪时输入框不显示麦克风。

### 5.11 图片与文件附件（含字段坑）

- **请求侧字段名**：服务端 `runtime/message_convert.py` 的 `_request_input_to_msgs`
  读的是扁平字段 `image_url` / `video_url` / `file_url`（文件另带 `filename`），
  **不是**老协议的 `{"type":"image","source":{"url":...}}`。
  早期版本发 `source.url` 会被静默丢弃——图片进不了上下文，历史里也只剩文字。
  正确形状：
  ```json
  {"type":"image","image_url":"/home/u/.qwenpaw/workspaces/default/media/xxx.png"}
  ```
  回归用例见 `QwenPawApiTest`。
- **历史侧形状**：`GET /api/chats/{id}` 里 user 消息的图片块是
  `{"type":"image","image_url":"data:image/png;base64,..."}`，
  服务端把本地文件内联成 data URI（`_resolve_content_url` + base64 分支）。
  外链则原样返回 `http(s)://`。
- **渲染**：客户端不引入 Coil，`ChatImageLoader` 直接 OkHttp + BitmapFactory：
  `data:` 本地解码、`http(s)://` 直接下载、其余当作服务端本地绝对路径走
  `GET /api/files/preview/{绝对路径}`（实测 200，带 FileGuard 敏感路径检查，
  只允许 WORKING_DIR 内的文件）。大图先读尺寸再 `inSampleSize` 采样，避免 OOM。
- **图片缩放**：`ImageViewerDialog` 支持双指缩放与拖动，单击关闭。

### 5.12 置顶 / 归档 / 分组

- 置顶：`PUT /api/chats/{id}` body `{"pinned":true|false}`，与重命名共用端点。
  客户端排序改为「置顶 → 运行中 → 最近活跃」。
- 归档：`POST /api/chats/{id}/archive`，**运行中的会话会被拒绝**（需先停止）；
  取消归档 `POST /api/chats/{id}/unarchive`（幂等）。
  `GET /api/chats?archived=false|true`，不传参返回全部。
- 分组：`GET /api/chats/groups` 返回 `default` / `cron` / `subagents`；
  `PUT /api/chats/{id}` body `{"group_id":"..."}` 移动分组，传 null 清空。

### 5.13 上下文用量与思考模式

- 用量：SSE 的 `turn_usage` 帧给 `context_usage = {estimated_tokens,
  max_input_length, context_usage_ratio}`，**只有整轮粒度**，没有按消息拆分。
  客户端的「用量明细」面板展示总量 + 进度条，并按字符数估算各消息占比（标注为估算）。
- 思考模式：`GET/PUT /api/chats/{id}/thinking`。响应里 `control.kind` 描述模型
  是否可调（`effort` / `budget` / `unsupported` / `unknown`），`control.efforts`
  列出可选项。`level=inherit` 表示跟随模型默认；模型不支持时 PUT 返回 422。
  客户端只在 `efforts` 非空或 `supports_off` 时才显示入口。
- 模型切换：`PUT /api/chats/{id}/model` body `{"provider_id":...,"model":...}`，
  传 null 恢复智能体默认。`GET /api/models` 列供应商，
  `GET /api/models/{provider_id}/pool` 列模型池。

### 5.14 Token 用量统计

`GET /api/token-usage`（**注意是连字符**，不是 `/api/token/usage`），
查 `start_date` / `end_date` / `model` / `provider` 返回
`{total_prompt_tokens, total_completion_tokens, total_cache_read_tokens,
cache_hit_rate, total_calls, by_model{...}, by_date{...}}`。
客户端的「用量统计」页做只读展示。

### 5.15 会话摘要 / 预览

服务端 `ChatSpec` **没有 summary 字段**。客户端在列表刷新时，对最近 20 个会话各拉
一次 `GET /api/chats/{id}`，从映射后的消息里摘出最后一条有内容的消息前 60 字作预览，
用内存 map 按 `updated_at` 变化刷新，避免每次刷新都打一堆详情请求。

带附件的消息服务端会注入一行「用户上传文件，已经下载到 &lt;路径&gt;」，它既不是用户原话
也不是助手回复，预览时按前缀跳过。

### 5.16 包体体积与 ABI 取舍

早期 debug APK 达到 **133MB**，构成如下：

| 组成 | 体积 | 处理 |
|---|---|---|
| sherpa-onnx `.so` ×4 ABI | 约 98MB | **只保留 `arm64-v8a`**（`ndk.abiFilters`） |
| dex（debug 未优化） | 44MB | release 开 R8 后大幅缩水 |
| MiSans 字体 ×2 | 15.8MB | **改为运行时可选取下载**（见 5.17） |
| 其它 | 约 10MB | — |

只留 arm64-v8a 后，`.so` 从 98MB 降到 22MB，native 一项就砍掉约 76MB。
代价是 x86/x86_64 模拟器装不上——需要时在本地临时放开对应 ABI，**不要提交**。

改完 debug APK 为 **40.6MB**（原 133.65MB，降约 70%）。

### 5.17 MiSans 字体改为可选下载

字体体积 15.8MB 且只有部分用户需要，因此不再随 APK 打包：

- 默认用系统字体；设置页「使用 MiSans 字体」开关**默认关闭**，且字体未下载时置灰不可点。
- 点「下载」（约 16MB）后落到 `files/fonts/`，两个字重分别对应
  `MiSans-Static.ttf`(usWeightClass=400) 与 `MiSans.ttf`(usWeightClass=500)。
- 下载策略：先都下到 `.tmp`，**两个字重都成功后再原子改名**，避免半成品被误判为「已安装」；
  任一失败则清空全部并回到未安装。
- Theme 侧不再引用 `R.font.*`，改为把运行时 `File` 包成 Compose `Font(file, weight)`
  （`AndroidFontKt.Font-Ej4NQ78`，即 `Font(File, …)` 重载）。
- **来源与许可**：字体来自 GitHub 镜像仓库 `adivenxnataly/MiSans`，版权归小米科技有限责任公司。
  MiSans 许可协议允许免费商用，但**明确禁止再分发字体本体**——所以本仓库不提交字体文件，
  App 也不随包分发，只在用户主动点击时从其 GitHub 原址下载，并在设置页与关于页标注出处。
  仓库内 `.gitignore` 已排除 `res/font/*.ttf`、避免误提交。


