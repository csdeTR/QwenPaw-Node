# 服务端协议与已知坑

本文记录 QwenPaw Android 客户端与服务端之间所有已实测的接口形状、字段语义与失败模式。
记录基准为 QwenPaw **2.2.1**，本机实例以 `--host 0.0.0.0` 启动，监听 `:8088`。
文中每一条都来自实际抓包、源码阅读或设备联调，不存在仅凭官方文档推断的内容。

阅读约定：客户端实现**以本文为准**；服务端升级后应逐条回归本文，再决定是否需要改代码。
分层结构与状态流转见 [architecture.md](architecture.md)。

---

## 一、鉴权

服务端的鉴权是可选的，由环境变量 `QWENPAW_AUTH_ENABLED` 控制。

### 状态查询

`GET /api/auth/status` 返回：

```json
{"enabled": false, "has_users": false}
```

`enabled` 为 `false` 时，所有业务接口直接可用，请求不需要携带任何凭据。
这是本机开发的默认状态，也是客户端首次连接时最简单的路径。

### 登录

开启鉴权后，客户端调用 `POST /api/auth/login` 换取 token，
之后每个请求都要带上请求头 `Authorization: Bearer <token>`。
token 失效时服务端返回 401，客户端需要清除凭据并回到连接页。

**登录接口的 404 多半不是「没有这个端点」。** 服务端部署在 openresty 之后时，
`http://<域名>` 会被 301 跳到 https。OkHttp 跟随 301 会把 POST 改写成 GET
并丢弃请求体，`POST /api/auth/login` 于是变成 `GET /api/auth/login`，
服务端对这个方法没有路由，返回 404。客户端因此把登录 404 单独归为
「请求未落到登录端点」，文案指向地址与协议，而不是复用 `GET /api/chats/{id}`
的 404 文案。

同一现象也解释了验证地址时为什么要按主机判协议：公网域名默认走 https，
内网地址默认走 http，写错时表现为 404 或连接失败，而不是明确的协议错误。

### 客户端存储

token 与实例凭据存放在 `EncryptedSharedPreferences` 中，密钥由 Android Keystore 托管。
部分设备在 Keystore 初始化时会抛出异常（常见于系统刚恢复出厂或生物识别凭据被重置），
因此实现上保留了降级路径：捕获该异常后改用普通 `SharedPreferences` 存储。
降级意味着凭据以明文落盘，这是可用性优先于机密性的取舍，只在此类设备上发生。

---

## 二、会话

### 端点一览

| 方法与路径 | 作用 | 返回或参数要点 |
| --- | --- | --- |
| `GET /api/version` | 探测服务端版本 | `{"version":"2.2.1"}` |
| `GET /api/agents` | 列出智能体 | 连接时用来校验 `agent_id` 是否存在 |
| `GET /api/chats` | 列出会话 | 每项含 `status`（`idle` / `running`）与 `updated_at` |
| `POST /api/chats` | 新建会话 | 需 `name`、`session_id`、`user_id` |
| `GET /api/chats/{id}` | 拉取完整历史 | `{"messages":[...],"status":"..."}` |
| `GET /api/chats/{id}/status` | 只查运行状态 | `{"status":"idle"}`，比拉整个历史轻得多 |
| `PATCH /api/chats/{id}` | 重命名 | 与置顶共用同一路径，见第七节 |
| `DELETE /api/chats/{id}` | 删除会话 | — |

> 同一路径 `/api/chats/{id}` 上，重命名记录为 `PATCH`，置顶与分组记录为 `PUT`。
> 两者都作用于同一个资源，实现上以实测可用的动词为准；改动这一处前应重新抓包确认。

### 两个必须注意的语义

**第一，列表顺序不可依赖。** 服务端返回的会话列表没有稳定的排序保证，
运行中的会话有可能排在很靠后的位置。客户端因此不能直接采用服务端顺序，
而应自行排序：先按 `status == "running"` 置顶，再按 `updated_at` 倒序。
若省掉这一步，用户会看到正在生成回复的会话沉在列表底部，误以为任务丢失。

**第二，`session_id` 与 chat id 可能不是同一个值。**
客户端在新建会话时传入形如 `mobile-xxxxxxxx` 的 `session_id`，
它才是发起 SSE 对话时真正要使用的标识；而 `GET/DELETE` 等管理类端点用的是 chat id。
把两者混用会得到 404，或把消息发进一个不存在的会话。

---

## 三、SSE 流

### 请求

对话通过 `POST /api/console/chat` 建立，请求体形如：

```json
{
  "session_id": "mobile-xxxxxxxx",
  "user_id": "mobile-user",
  "input": [{"role": "user", "content": [{"type": "text", "text": "你好"}]}],
  "reconnect": false
}
```

`reconnect` 的用法见第四节。`input` 为数组，可以同时携带文本与附件块，
附件的字段形状见第八节。

### 帧类型

帧按 `object` 字段分流。实测共六类：

| 帧 | 含义与处理 |
| --- | --- |
| `object=response` | 整轮的生命周期，`status` 取 `created` / `in_progress` / `completed` / `failed` |
| `object=message` | 声明一条消息及其类型，`type` 取 `message` / `reasoning` / `plugin_call` / `plugin_call_output` |
| `object=content` 且 `delta=true` | 增量文本，带 `msg_id`、`index`、`text` |
| `type=turn_usage` | 本轮的 token 用量；`completed` 之后还会额外补一帧 |
| `type=rate_limited` | 触发限流 |
| `type=heartbeat` | 保活帧，直接忽略 |

### 三个容易写错的点

**增量帧不携带类型。** `object=content` 的增量帧只有 `msg_id`、`index` 与 `text`，
无法从帧本身判断这段文字属于正文、思考还是工具输出。
类型必须由先前到达的 `object=message` 帧声明，客户端据此维护 `msg_id → Kind` 映射，
后续增量再按 `msg_id` 查表。若忽略这一步，所有内容都会被当成正文，
思考过程会混进回复里显示。

**`rate_limited` 自带 `error` 字段。** 这一帧本身含有 `error`，
如果解析器的通用 `error` 分支写在类型判断之前，限流帧会被误判为整轮失败，
界面上直接显示错误气泡，而实际服务端仍在正常排队。
因此类型判断必须优先于通用错误判断。

**历史里的 `msg_id` 会重复。** 同一个 `msg_id` 在 `GET /api/chats/{id}` 的返回中可能出现多次，
成因是 reasoning 与正文共用同一 id，或一条 assistant 消息被拆成多条记录。
直接把 `msg_id` 用作 `LazyColumn` 的 key，会抛出
`IllegalArgumentException: Key ... was already used` 并导致崩溃。
客户端的处理是在 `HistoryMapper.uniqueIds` 中为重复 id 追加序号，
保证映射后的渲染单元 id 唯一。

### 工具调用的数据结构

工具相关数据放在 `content[].data`：

- `plugin_call` → `{call_id, name, arguments}`
- `plugin_call_output` → `{call_id, name, output}`

两者按 `call_id` 配对，渲染为一张卡片。配对失败（例如只收到 `plugin_call`
而输出帧尚未到达）时应显示「执行中」而不是空白卡片。

---

## 四、断线与停止

### 客户端断开不影响服务端

SSE 连接断开后，服务端仍会继续把这一轮跑完。这一点决定了重连的实现方式：
重新进入会话时，用 `reconnect: true` 再发一次 `input` 为空的请求，即可挂回正在运行的流，
已生成的内容会继续推过来。若误以为断开等于任务中止而在客户端本地标记完成，
用户会看到一条被截断的回复。

### 停止之后必须重拉历史

`POST /api/console/chat/stop?chat_id=<chat id>` 返回 `{"stopped": true}`。

停止后服务端保留了用户消息与已经生成的部分内容。因此客户端在停止后**必须重新拉取一次历史**
再渲染；如果只把本地状态改成「已停止」，那些已经落库的部分内容在界面上就会消失，
用户下次进入会话时又会突然出现，造成内容不一致的错觉。

### 取消协程时的收尾位置

取消承载流的 `Job` 会抛出 `CancellationException`。协程取消后，
写在取消点之后的常规收尾逻辑不会继续执行，`running = false` 这一步就永远不生效，
发送按钮会一直停留在「停止」状态。

正确的做法是把收尾逻辑放进一个新启动的协程（例如 `CoroutineScope(Dispatchers.Main).launch`
或注入的独立作用域），由它负责复位状态。

---

## 五、并发与错误

### 并发发送

同一个 chat 并发发送会返回 409，响应体为：

```json
{"detail": "A task is already running for this chat. Wait for it to finish or use a different session_id."}
```

客户端在 `running == true` 时直接拦截，不依赖服务端返回 409。
这样既少一次无效请求，也能在离线状态下给出即时反馈。

### 连接失败的文案不统一

OkHttp 在连接失败时抛出的异常与文案并不一致，实测至少两种形态：

- 端口拒绝：`Failed to connect to /10.0.2.2:8088`
- 丢包超时：`failed to connect to /10.0.2.2 (port 8088) from /10.0.2.15 (port x) after 20000ms`

第二种情况**不会**抛出 `ConnectException`。只按异常类型分支判断会漏掉这一整类失败，
用户只会看到一句毫无意义的技术异常。因此实现上必须同时匹配文案，
并把两类失败统一映射为中文提示。

---

## 六、审批

### 端点

- `GET /api/approval/list` → `{"pending_approvals":[...], "count":n}`
  列表项包含 `requestId`、`sessionId`、工具名与摘要。
- `POST /api/approval/approve`
- `POST /api/approval/deny`

### 没有推送通道，只能轮询

QwenPaw 不提供推送能力，官方 Console 自身也是每 2.5 秒轮询一次
`GET /api/console/push-messages`。客户端采用同样的节奏轮询审批列表。

轮询实现有一个必要的细节：若本轮返回的审批集合与上一轮完全一致，
就不应更新 state。否则每 2.5 秒都会产生一个新的 UI 状态对象，
导致整个界面周期性重组，表现为界面在空闲时也持续掉帧。

### 联调时的注意事项

审批级别写在 `~/.qwenpaw/workspaces/default/agent.json` 的 `approval_level` 字段。
默认值是 `AUTO`，只有改成 `ASK` 才会对高危命令弹出审批。
修改前应先备份该文件，测完立刻还原为 `AUTO`；忘记还原会让后续所有真实使用都带审批弹窗。

---

## 七、置顶、归档与分组

### 置顶

`PUT /api/chats/{id}`，请求体 `{"pinned": true|false}`。
该端点与重命名共用，因此只传 `pinned` 时不会影响名称。

客户端排序因此变为三段：置顶、运行中、最近活跃。

### 归档

- `POST /api/chats/{id}/archive`：归档。**运行中的会话会被拒绝**，必须先停止生成。
- `POST /api/chats/{id}/unarchive`：取消归档，幂等。
- `GET /api/chats?archived=false`：只列未归档。
- `GET /api/chats?archived=true`：只列已归档。
- 不传 `archived` 参数：返回全部。

### 分组

`GET /api/chats/groups` 返回三个内置分组：`default`、`cron`、`subagents`。
实测响应字段为 `id` / `name` / `order` / `kind` / `source` / `pinned`；
`kind=default` 表示未分类，`cron` 为定时任务，`subagents` 为子代理，用户自建的为 `custom`。

排序按 `pinned` 优先、其次 `order` 升序，与服务端内部 `_ordered_groups()` 一致。

移动分组用 `PUT /api/chats/{id}`，请求体 `{"group_id": "..."}`，传 `null` 表示清空分组。

客户端支持新建、改名与删除自建分组：

| 操作 | 请求 | 响应 |
| --- | --- | --- |
| 新建 | `POST /api/chats/groups`，体 `{"name": "..."}` | 完整 `ChatGroupDto`，`kind=custom` |
| 改名 / 置顶 | `PUT /api/chats/groups/{id}`，体 `{"name": ...}` 和/或 `{"pinned": ...}` | 完整 `ChatGroupDto` |
| 删除 | `DELETE /api/chats/groups/{id}` | `{"success": true, "group_id": "..."}` |

三处实测约束：

1. **内置分组（`kind` 为 `default` / `cron` / `subagents`）不可改名或置顶。**
   服务端不会给出可读的报错，客户端必须先自行判断 `kind`。
2. **`PUT` 至少要带一个字段**，否则返回 400。
3. **新建后必得 `custom` 且 `source` 为 `null`。** 删除只会移除分组本身，
   组内会话回到 `default`，不会被一并删除。

**空的自建分组必须显示。** 用户刚建完必然还没有会话，列表里藏起来看起来像建失败。
客户端的分组渲染因此对内置分组过滤空桶，对 `custom` 保留。

QwenPaw 网页版的分组三态（按时间 / 按来源 / 不分组）来自其打包产物
`assets/zh-*.js` 中的 `groupByTime`、`groupBySource`、`groupByNone` 三个键，
单击按钮循环切换，顺序为 时间 → 来源 → 不分组。客户端与之一致。
时间分档同样是三档：今天、近 7 天、更早。

---

## 八、附件与图片

### 上传

`POST /api/console/upload`（multipart）返回：

```json
{"url": "/home/<user>/.qwenpaw/workspaces/default/media/..."}
```

注意这是一个**服务器本地绝对路径**，不是可访问的 HTTP 地址。
客户端不能直接拿它当 URL 加载。

### 请求侧字段形状（最容易踩的坑）

服务端在 `runtime/message_convert.py` 的 `_request_input_to_msgs` 中读取的是**扁平字段**：

- `image_url`
- `video_url`
- `file_url`（文件另带 `filename`）

而**不是**老协议里那种嵌套结构 `{"type":"image","source":{"url":...}}`。

早期版本按嵌套结构发送，服务端会**静默丢弃**该内容块：没有任何错误返回，
图片进不了模型上下文，历史里也只剩一段文字。排查过程完全是靠读服务端源码才定位到。

正确的请求形状：

```json
{"type": "image", "image_url": "/home/u/.qwenpaw/workspaces/default/media/xxx.png"}
```

回归用例见 `QwenPawApiTest`。

### 历史侧的图片形状

`GET /api/chats/{id}` 中，用户消息的图片块形如：

```json
{"type": "image", "image_url": "data:image/png;base64,..."}
```

服务端通过 `_resolve_content_url` 与其 base64 分支，把本地文件内联成 data URI。
如果是外链，则原样返回 `http(s)://` 地址。

### 渲染

客户端没有引入 Coil。理由是只需要支持三种地址形态（data URI、HTTP、服务端本地路径），
引入图片库带来的依赖体积与配置成本不成正比，而 `BitmapFactory` 已足够。

`ChatImageLoader` 的行为：

- `data:` 前缀 → 本地解码
- `http(s)://` → 直接下载
- 其余 → 当作服务端本地绝对路径，走 `GET /api/files/preview/{绝对路径}`

该预览端点实测返回 200。它带有 FileGuard 敏感路径检查，只允许读取 `WORKING_DIR` 内的文件，
因此传入任意绝对路径并不会变成任意文件读取。

大图先读取尺寸，再用 `inSampleSize` 采样，避免整张原图解码导致 OOM。

`ImageViewerDialog` 支持双指缩放与拖动，单击关闭。

### 实测记录

上传一张 64×64 的纯蓝 PNG，模型调用 `view_image` 后正确答出 RGB(74,107,181)，
说明扁平字段的图片确实进入了模型上下文。

### 工具交付的文件（`send_file_to_user`）

服务端**没有「已交付文件」列表接口**。智能体调用 `send_file_to_user` 时，
文件字节以 base64 内联在该次工具调用的输出里，历史消息中形如：

```json
{"type":"plugin_call","role":"assistant","content":[{"type":"data","data":{
  "call_id":"call_38d30734739842a996f508da","name":"send_file_to_user",
  "arguments":"{\"file_path\": \"/home/.../chatgpt_2022-12-05_esperanto.png\"}"}}]}

{"type":"plugin_call_output","role":"tool","content":[{"type":"data","data":{
  "call_id":"call_38d30734739842a996f508da","name":"send_file_to_user",
  "output":"[{\"type\":\"data\",\"id\":\"744e...\",\"source\":{\"type\":\"base64\",\"data\":\"iVBORw0KGgo...\"}}]"}}]}
```

三个要点：

1. **`output` 里没有文件名。** 文件名只能取 `arguments.file_path` 的 basename，
   调用与输出必须按 `call_id` 配对。SSE 的 `plugin_call_output` 事件同样带
   `name` 与 `output`，两条路径可以共用一个解析器（`DeliveredFiles.parse`）。
2. **`output` 是 JSON 字符串里再套一段 JSON。** 第一次解析得到的是字符串，
   还需要再解一层，取 `[0].source.data`，`source.type` 不是 `base64` 时忽略。
3. **`arguments.file_path` 由服务端写入，可能是绝对路径**，且不同工作区前缀不同。
   客户端只用它取文件名与展示路径，不据此拼接任何下载地址。

非图片类型不解码字节：客户端只显示文件名、路径与「已发送」，
把几 MB 到几十 MB 的 base64 解进内存没有用途。图片拼成 `data:<mime>;base64,<data>`，
交给 `ChatImageLoader` 的 data URI 分支解码。

解析完成后工具卡上的原始 `output` 会被置空，避免整份 base64 留在界面状态里。

---

## 九、上下文用量、思考模式与模型切换

### 上下文用量与单轮 token 账目

SSE 的 `turn_usage` 帧里，`context_usage` 与 `usage` **平级**：

```json
{
  "type": "turn_usage",
  "usage": {
    "provider_id": "dashscope", "model_name": "qwen3.8-flash",
    "prompt_tokens": 13654, "completion_tokens": 229,
    "cache_read_tokens": 10240, "cache_hit_rate": 74.99633806943021,
    "context_size": 131073
  },
  "context_usage": {
    "estimated_tokens": 264, "max_input_length": 131073,
    "context_usage_ratio": 0.20141447895447576
  }
}
```

三个必须记住的点：

1. **`context_usage_ratio` 是百分数**（实测 `10.54` 表示 10.54%，`0.2014` 表示 0.2%）。
   客户端统一用 `estimated_tokens / max_input_length` 折算，比字段本身更稳。
2. **`cache_hit_rate` 也是百分数**（实测 `74.99633806943021` 表示 75%）。
   解析时除以 100，避免界面与日志两处各折一次、结果不一致。
3. **服务端没有首字耗时字段。** 源码中不存在 `first_token`、`ttft`、`latency` 一类字段，
   客户端不显示该指标。

`GET /api/chats/{id}` 的历史消息把同一组数据放在
`metadata.metadata.qwenpaw_turn_usage.usage`，结构完全相同。

**但挂载范围与直觉不同：整轮的 `qwenpaw_turn_usage` 会重复出现在该轮的每一条
assistant 消息上**——reasoning、plugin_call、plugin_call_output 全都带，而不只是收尾那条。
实测一个 9 条消息的回合里，前 8 条与最后一条的 `prompt_tokens`/`completion_tokens`
完全一致。原样渲染会让多步回合的每段正文下面都重复同一组数字，因此客户端在
`HistoryMapper.markTurnEnd()` 里按回合去重，只让该轮最后一条正文保留统计。

服务端**只提供整轮粒度**，没有按消息拆分的用量数据。
客户端的「用量明细」面板因此展示总量与进度条，并按字符数估算各消息的占比，
界面上明确标注为估算，不伪装成精确值。

### 思考模式

`GET /api/chats/{id}/thinking` 与 `PUT` 同一路径。

响应中的 `control.kind` 描述模型是否可调，取值有四种：
`effort`、`budget`、`unsupported`、`unknown`。`control.efforts` 列出可选项。
`level = inherit` 表示跟随模型默认值。模型不支持时 `PUT` 返回 422。

客户端只在 `efforts` 非空或 `supports_off` 为真时才显示入口，
否则会给用户一个点了没有反应的开关。

### 模型切换

`PUT /api/chats/{id}/model`，请求体 `{"provider_id": ..., "model": ...}`。

**`null` 不是有效入参。** 实测四种写法全部失败：

| 请求体 | 结果 |
| --- | --- |
| `{"provider_id": null}` | 400，`Input should be a valid string` |
| `{"provider_id": ""}` | 400，`Model provider is unavailable` |
| `{"provider_id": "", "model": ""}` | 同上 |
| `{}` | 同上 |

因此「恢复默认」不能靠传空值，正确做法是先读 `GET /api/models/active`
拿到全局默认，再把它显式 `PUT` 到会话上：

```json
{"active_llm": {"provider_id": "a2a", "model": "CodeArts/deepseek-v4.1-flash-1017"},
 "effective_max_input_length": 1000000}
```

`DELETE /api/chats/{id}/model` 返回 `Method Not Allowed`，不存在清除端点。

查询侧：`GET /api/models` 列出供应商。

**不要用 `GET /api/models/{provider_id}/pool` 取会话可切换的模型列表。**
本机部署下该端点返回空数组，依赖它会得到「没有可用模型」的错误结论。
可选模型分散在 `GET /api/models` 每个供应商的三个数组里：
`models`（配置里写明的）、`extra_models`（额外添加的）、`discovered_models`（探测到的）。
客户端按此顺序合并、按 id 去重，并跳过 `enabled = false` 的供应商。

### 智能体审批模式

`GET /api/agents/{id}` 返回的 `approval_level` 是该智能体的审批模式，
取值 `STRICT` / `SMART` / `AUTO` / `OFF`。这是**智能体级**配置，
改动影响该智能体的全部会话，不存在会话级覆盖端点。

`PUT /api/agents/{id}` **要求提交完整的 `AgentProfileConfig`**。
本机实测该对象有 30 个字段（含 `channels`、`backend`、`workspace_dir`、`project_dirs` 等），
只提交 `{"approval_level": "..."}` 会把其余字段抹掉。客户端因此先 `GET` 原对象，
用原始 JSON 替换掉 `approval_level` 后整体 `PUT` 回去。

### 工作区目录

`GET /api/workspace/project-directory` 返回当前目录：

```json
{"path": "/home/u/proj", "name": "proj", "is_workspace_default": false,
 "workspace_dir": "/home/u/.qwenpaw/workspaces/default", "exists": true}
```

`PUT` 同一路径，body 为 `{"path": "/abs/path"}`；`path` 传 JSON `null` 恢复工作区默认目录。
路径不存在或不是目录时返回 400。这里的路径是**服务端**上的绝对路径，与手机本地文件系统无关。

`path` 与 `workspace_dir` 的区别：处于默认目录时 `path` 可能为空，
此时客户端应回退显示 `workspace_dir`，否则输入框空白让人不知道该填什么。

---

## 十、Token 用量统计

路径是 `GET /api/token-usage`——**中间是连字符**，不是 `/api/token/usage`。

查询参数：`start_date`、`end_date`、`model`、`provider`。

返回字段：

- `total_prompt_tokens`
- `total_completion_tokens`
- `total_cache_read_tokens`
- `cache_hit_rate`
- `total_calls`
- `by_model{...}`
- `by_date{...}`

客户端的「用量统计」页做只读展示。

---

## 十一、会话摘要与预览

服务端的 `ChatSpec` **没有 summary 字段**，也不存在现成的预览文本接口。
因此列表页的摘要完全是客户端行为：

1. 列表刷新后，对最近 20 个会话各拉一次 `GET /api/chats/{id}`；
2. 从映射后的消息中取最后一条有内容的消息，截取前 60 字作为预览；
3. 结果放进内存 map，只在 `updated_at` 变化时刷新。

若每轮刷新都为全部会话打详情请求，首屏请求量会随会话数线性增长，
因此只取前 20 个并做变化判断。

另有一个需要跳过的干扰项：带附件的消息，服务端会注入一行
「用户上传文件，已经下载到 &lt;路径&gt;」，这一行既不是用户原话也不是助手回复。
预览时按该前缀跳过，否则列表里每条带附件的会话都显示同一句模板文字。

---

## 十二、通知

QwenPaw 没有推送通道，客户端只实现**本地**通知。

触发条件：进程内收到 `completed` 帧，且 `AppVisibility.visibleCount == 0`
（即应用不在前台）时，发一条「回复已完成」通知。
应用在前台时不发，避免在用户正看着屏幕时打扰。

由此产生一个必然的边界：应用进程被系统杀掉后不会再有任何提醒。
草稿与历史仍可从服务端补齐，但「后台完成提醒」这个能力在进程存活之外无法提供。
这是放弃实时推送的代价，属于有意识的取舍而非缺陷。

---

## 十二之二、服务端不提供的两类数据

有两类数据看起来应当由服务端提供，实际没有接口，客户端已按本地实现处理。

### 头像

服务端**没有消息级或用户级头像接口**。`MessageMetadataDto` 只有
`timestamp` / `finished_at` / `original_id` / `original_name` / `metadata`，
没有任何图片引用。

因此头像完全存在客户端：`filesDir/avatars/` 下 `user.png|gif` 与 `agent.png|gif`。
静态图裁剪成正方形并压到 500×500；GIF 原样保存，由 Coil 的逐帧解码器播放。
头像不会上传，也不会随实例同步。

### 运行日志

客户端另有「运行日志」，记录**本机客户端**的事件（网络请求、流建立与中断、
轮询失败、存储操作、语音），与 QwenPaw 服务端日志是两回事。

日志只存在进程内的环形缓冲（上限 2000 条），默认关闭，关闭时所有写入是空操作。
导出格式固定为 `HH:mm:ss.SSS [类别] tag · 内容`，导入按同样格式解析，
无法识别的行原样保留为界面条目。敏感信息在写入前脱敏：
`Authorization: Bearer <token>` 与 URL 中的 `token=` / `access_token=` 一律替换为 `***`。

---

## 十三、已知取舍

以下四项在项目范围内明确不做，且不是待办：

- **不做离线自动补发。** 断网时保留草稿与失败气泡，由用户点「重试」重新发送。
- **不做实时推送与常驻后台长连接。** 没有前台服务，也不做进程保活，进程被杀即失联。
- **不做 Mermaid 渲染。** 代码块降级为可复制的源码文本。
- **不做 L3 管理面**（智能体配置、技能管理、日志查看）。这类能力需要服务端管理 API 稳定后再评估。

服务端已提供而客户端尚未接入的能力另见 [待办清单.md](../待办清单.md)。

---

## 十四、语音输入（离线）

语音输入全程在手机本地完成，不依赖系统语音服务，也不会上传音频。

选择自建引擎的原因：Android 自带的 `SpeechRecognizer` 需要设备预先安装识别服务，
实测测试机上没有，调用后没有任何结果返回。因此改为内置离线引擎，避免依赖设备厂商。

### 引擎与模型

- 引擎：[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)（k2-fsa 团队，Apache-2.0）。
  文档见 <https://k2-fsa.github.io/sherpa/onnx/>，Android AAR 从
  [Releases](https://github.com/k2-fsa/sherpa-onnx/releases) 获取。
  仓库内为 `app/libs/sherpa-onnx-1.13.3.aar`。
- 模型：[sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models)，
  中英流式 zipformer2，int8 量化。压缩包 126MB，解压后约 168MB。
  下载地址固定写在 `SpeechModelManager.MODEL_URL`，由用户在设置页手动触发下载。

### 解压

模型是 `tar.bz2`。解压使用 [commons-compress](https://commons.apache.org/proper/commons-compress/)
的 `BZip2CompressorInputStream`，边解压边喂给 `TarArchiveInputStream`，
不落中间文件，避免在手机上额外占用一份完整压缩包的存储空间。

### 采集参数与崩溃风险

`AudioRecord` 以 16kHz 单声道采集，每 0.1 秒送一块数据给识别器。

块长必须固定。sherpa 会根据每块传入的采样数反推采样率，
块长不固定时会报 `You changed the input sampling rate` 并直接崩溃。
若设备实际采样率与 16kHz 不同，先用 `LinearResampler` 折回 16kHz 再送入。

### 收尾

用户点「说完了」时，只调用 `AudioRecord.stop()` 并置一个停止标志，
让采集协程自己走到 `inputFinished()` 完成收尾。
**不能直接取消采集协程**——取消会跳过收尾流程，把状态永久卡在「录音中」。

### 入口开关

设置项为「显示语音按钮」。开关打开但模型尚未下载时会弹出说明并引导去下载；
模型未就绪时，输入框不显示麦克风。

---

## 十五、包体体积与 ABI 取舍

早期的 debug APK 达到 **133MB**。构成如下：

| 组成 | 体积 | 处理方式 |
| --- | --- | --- |
| sherpa-onnx `.so` × 4 ABI | 约 98MB | 只保留 `arm64-v8a`（`ndk.abiFilters`） |
| dex（debug 未优化） | 44MB | release 开 R8 后大幅缩小 |
| MiSans 字体 × 2 | 15.8MB | 改为运行时可选取下载 |
| 其它 | 约 10MB | — |

只保留 `arm64-v8a` 后，`.so` 从 98MB 降到 21.4MB，单是 native 一项就减少约 76MB。

这一轮调整后 debug APK 为 **40.6MB**（原 133.65MB，降幅约 70%）。

> **后续变化**：现在改为 `splits.abi` 按架构拆包，四个架构各出一个 APK，
> 都不再是单包。体积与实测状态见 [release.md](release.md) 7.1、7.2 节。
> `splits.abi` 与 `ndk.abiFilters` 二选一，同时存在时后者被忽略。

> **当前实测值**（此后又接入了更多功能，数字已变化）：`arm64-v8a` release **23.4MB**
> （native 21.4MB + dex 3.0MB，R8 把 dex 从 44MB 压到 3.0MB），debug **41MB**。
> release 的体积几乎全部是 sherpa-onnx 的 `.so`，已无压缩空间。
> 数字会随功能增长而变化，引用时以实测为准。

---

## 十六、MiSans 字体改为可选下载

字体本体 15.8MB，且只有部分用户需要，因此不再随 APK 打包。

### 行为

- 默认使用系统字体。设置页「使用 MiSans 字体」开关**默认关闭**，
  且字体未下载时该开关置灰不可点。
- 点击「下载」（约 16MB）后落到 `files/fonts/`。两个字重分别对应
  `MiSans-Static.ttf`（`usWeightClass = 400`）与 `MiSans.ttf`（`usWeightClass = 500`）。
- 下载策略：两个字重先都下载到 `.tmp`，**全部成功后再原子改名**。
  这样做是为了避免只完成一半的文件被后续逻辑误判为「已安装」；
  任一失败则清空全部临时文件并回到未安装状态。

### 字体如何接入 Compose

Theme 侧不再引用 `R.font.*`，而是把运行时的 `File` 包成 Compose 字体：
`Font(file, weight)`，对应 `AndroidFontKt.Font-Ej4NQ78` 重载。

### 来源与许可

字体来自 GitHub 镜像仓库 `adivenxnataly/MiSans`，版权归小米科技有限责任公司所有。

MiSans 许可协议允许免费商用，但**明确禁止再分发字体本体**。
因此本仓库不提交字体文件，应用也不随包分发，只在用户主动点击时从其 GitHub 原址下载，
并在设置页与关于页标注出处。仓库的 `.gitignore` 已排除 `res/font/*.ttf`，避免误提交。