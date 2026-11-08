# 服务端协议与已知坑

本文记录 QwenPaw Android 客户端与服务端之间所有已实测的接口形状、字段语义与失败模式。
记录基准为 **2.2.2.post1** 与 **2.3.0b2** 两个版本（LXC 容器 `qp-test` / `qp-test-prerelease`），
两版差异单独列在第 17 节。文中每一条都来自实际抓包、源码阅读或设备联调，
不存在仅凭官方文档推断的内容。

阅读约定：客户端实现**以本文为准**；服务端升级后应逐条回归本文，再决定是否需要改代码。
分层结构与状态流转见 [architecture.md](architecture.md)。

---

## 1、鉴权

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

### 401 的四种成因

服务端的 401 只有一句话是不够用的：登录密码打错、token 过期、反向代理拦下
这三种情况的处置完全不同。实测三种 `detail` 与一种响应头：

| 来源 | 响应 | 判据 |
| --- | --- | --- |
| 反代 `auth_basic` | 401 + `WWW-Authenticate: Basic realm="…"` | 响应头含 `Basic`（不区分大小写） |
| 登录接口 | 401 + `{"detail":"Invalid username or password"}` | detail 含 `Invalid username or password` |
| 鉴权中间件 | 401 + `{"detail":"Invalid or expired token"}` | detail 含 `Invalid or expired token` |
| 鉴权中间件（未带凭据） | 401 + `{"detail":"Not authenticated"}` | detail 含 `Not authenticated` |

**`WWW-Authenticate` 必须优先于 detail。** 这个头只有反代会加，服务端自身从不发
（`AuthMiddleware` 的两处 401 都是裸 `Response`，不带该头）。反代拦下时 detail
可能是反代自己的 HTML 或空串，按 detail 猜必然猜错。

「未带凭据」与「凭据过期」在客户端要分开：前者是「还没登录」，后者是「登录失效」。
判据是这次请求有没有带过 token。

### 反向代理的 Basic 与 Bearer 争用同一个头

nginx / openresty 的 `auth_basic` 从 `Authorization: Basic …` 取值，QwenPaw 自身
读 `Authorization: Bearer …`。**一个请求只能带一个 `Authorization`。**
核对 `AuthMiddleware._extract_token`（`qwenpaw/app/auth.py`）后确认，服务端支持的提取顺序是：

1. `Authorization: Bearer <token>`；
2. 请求头 `connection` 含 `upgrade` 时读 `?token=`；
3. 其余情况读 `?token=`。

因此两层同时开启时，客户端把 `Authorization` 让给 Basic，QwenPaw 的 token 走查询参数
`?token=<token>`。**没有** `X-` 形式的独立 token 头可用。

| 层 | 凭据 | 位置 |
| --- | --- | --- |
| 反向代理 | Basic 账号密码 | `Authorization: Basic <base64(user:password)>` |
| QwenPaw | 登录 token | `?token=<token>` |

**代价：token 会出现在反代的 access log 里。** 不开反代时不走这条路，
token 仍只在 `Authorization` 头里，不进 URL。客户端自身的 `RunLog` 不记录 query，
不受影响。

四处请求必须一起改，漏一处的表现各不相同：

| 位置 | 漏改的表现 |
| --- | --- |
| `QwenPawApi.baseBuilder`（REST + 两种 SSE） | 发消息 401，版本探测正常 |
| `ChatImageLoader.load`（自己拼 Header） | 文字能出来、图片一直转圈 |
| 验证阶段的 `versionWith` / `authStatus` | 验证看不到真实的登录状态 |
| `QwenPawApi.login`（登录换 token） | 登录页报「用户名或密码不正确」，反代 access log 里用户字段是 `-` |

**`/api/auth/status` 与 `/api/auth/login` 也在反代之后。** 这两条是鉴权中间件的公开路径，
但 `auth_basic` 拦在更前面，公开与否不影响反代先要凭据。2026-11-06 的判定
`POST /api/auth/login` 实测就是 401（反代），客户端却把它翻译成密码错——
区分方法见下一段。

**`Not authenticated` 是三句合一。** 服务端对「没带 token」「token 过期」「token 被吊销」
返回同一个 detail，客户端只能靠这次请求是否带过 token 来分：没带就是「还没登录」，
带了才是「登录已失效」。把两者合并成一句「登录已失效」会让从没登录过的用户去找一个不存在的失效状态。

反代拦下时**不是「地址不可达」**：换下一个地址同样会被拦，做地址回退没有意义。
客户端的 `verify` 因此把它单独判成 `proxyAuthRequired`，就地展开凭据输入。

### 客户端存储

token 与实例凭据存放在 `EncryptedSharedPreferences` 中，密钥由 Android Keystore 托管。
部分设备在 Keystore 初始化时会抛出异常（常见于系统刚恢复出厂或生物识别凭据被重置），
因此实现上保留了降级路径：捕获该异常后改用普通 `SharedPreferences` 存储。
降级意味着凭据以明文落盘，这是可用性优先于机密性的取舍，只在此类设备上发生。

反向代理的 Basic 账号与密码同样存在这份加密偏好里（`basicUser` / `basicPassword`），
与 QwenPaw 的 `username` / `token` 是两套独立字段。**两个都非空才生效**：
只填一项视为未配置，让请求直接打到服务端，而不是拿半组凭据反复撞 401。

**登录接口的 404 多半不是「没有这个端点」。** 服务端部署在 openresty 之后时，
`http://<域名>` 会被 301 跳到 https。OkHttp 跟随 301 会把 POST 改写成 GET
并丢弃请求体，`POST /api/auth/login` 于是变成 `GET /api/auth/login`，
服务端对这个方法没有路由，返回 404。客户端因此把登录 404 单独归为
「请求未落到登录端点」，文案指向地址与协议，而不是复用 `GET /api/chats/{id}`
的 404 文案。

同一现象也解释了验证地址时为什么要按主机判协议：公网域名默认走 https，
内网地址默认走 http，写错时表现为 404 或连接失败，而不是明确的协议错误。

---

## 2、会话

### 端点一览

| 方法与路径 | 作用 | 返回或参数要点 |
| --- | --- | --- |
| `GET /api/version` | 探测服务端版本 | `{"version":"2.2.2.post1"}`；2.3.0 起为 `{"version":"2.3.0b2"}` |
| `GET /api/agents` | 列出智能体 | 连接时用来校验 `agent_id` 是否存在 |
| `GET /api/chats` | 列出会话 | 每项含 `status`（`idle` / `running`）与 `updated_at` |
| `POST /api/chats` | 新建会话 | 需 `name`、`session_id`、`user_id` |
| `GET /api/chats/{id}` | 拉取完整历史 | `{"messages":[...],"status":"..."}` |
| `GET /api/chats/{id}/status` | 只查运行状态 | `{"status":"idle"}`，比拉整个历史轻得多 |
| `PATCH /api/chats/{id}` | 重命名 | 与置顶共用同一路径，见第 7 节 |
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

## 3、SSE 流

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

`reconnect` 的用法见第 4 节。`input` 为数组，可以同时携带文本与附件块，
附件的字段形状见第 8 节。

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

## 4、断线与停止

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

### 「无法连接服务器，显示本地缓存」的三种成因

这条提示只在 `GET /api/chats/{id}` 抛出异常时出现，可能来自三类完全不同的原因：

| 原因 | 表现 | 判别 |
| --- | --- | --- |
| 网络或地址不可达、超时 | 任何对话都失败 | 用 `/api/version` 探测，失败即网络问题 |
| 响应解析失败 | 特定对话失败，其它正常 | 服务端日志正常，curl 能拿到 200 |
| 对话 id 不匹配（404） | 打开后落到空对话并提示断线 | 请求的 id 不是 `/api/chats` 的 `id` |

实测「杂谈」（`ba703f01-646e-4e3c-a565-e3892b367b52`，231 条消息、928 KB、
响应约 1.7 s）在 2.2.2.post1 上正常返回并能在客户端完整渲染，说明大响应本身不是诱因；
该对话的 221 个 usage 对象里 `cache_hit_rate` 都是数值而非 `null`。
排查同类问题应先区分上表三类，再看 `GET /api/chats/{id}` 的实际状态码。

---

## 5、并发与错误

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

## 6、审批

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
合法取值只有 `STRICT`、`SMART`、`AUTO`、`OFF`，服务端默认 `AUTO`，
其中 `AUTO` 下只有 `guarded_tools` 名单里的工具才触发审批。

**不存在 `ASK` 这个档位。** `qwenpaw/security/tool_guard/execution_level.py` 的
`ToolExecutionLevel.from_config` 对无法解析的值一律回退成 `AUTO`，既不报错也不打日志，
因此写错档位只能靠现场表现察觉。需要每条工具调用都审批时改 `STRICT`。

修改前应先备份该文件，测完用 `diff` 核对后还原；忘记还原会让后续所有真实使用都带审批弹窗。

---

## 7、置顶、归档与分组

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

## 8、附件与图片

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

## 9、上下文用量、思考模式与模型切换

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

## 10、Token 用量统计

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

### `cache_hit_rate` 是百分数，且可以是 `null`

`cache_hit_rate` 在 OpenAPI 里声明为 `number | null`：无缓存命中时返回 `null`，
有命中时返回 **0–100 的百分数**（实测对话里有 `1.709`、`45.54`），不是 0–1 的比例。
客户端按比例渲染会把 45.54 显示成 4554%，按百分比渲染才对。

`null` 这一形状只在干净实例上出现。客户端 DTO 若把它声明为非空 `Double`，
kotlinx.serialization 会抛 `SerializationException`；该异常若与「连不上」共用一条
`runCatching` 分支，就会被误报成「无法连接服务器」。解析失败与请求失败必须分开处理：
前者说明服务端在线但响应形状超出预期，后者才是网络或地址问题。

---

## 11、会话摘要与预览

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

### 会话标题的自动生成

服务端会在首轮对话结束后向模型要一个 ≤ 6 个词的标题，但触发条件很窄。
`console.py` 的两处判断缺一不可：

```python
# 1) 把占位名换成首条消息
if first_text and not chat.last_finished_at and chat.meta.get("console_placeholder_name") == chat.name:
    chat = await patch_chat_if_name_matches(chat.id, chat.name, ChatUpdate(name=name))
# 2) 只有名字等于首条消息时才派发标题生成
if first_text and chat.name == name:
    asyncio.create_task(generate_and_update_title(..., placeholder_name=name))
```

`name` 来自 `_extract_placeholder_name(content_parts)`：首块文本非空时返回
`(first_text, first_text)`，否则返回 `("Media Message", "")`。

**结论：客户端新建会话时必须写 `meta.console_placeholder_name`，且值与 `name` 相同。**
否则两处判断同时落空，标题永远不会生成，会话长期停在截断后的名称上
（客户端此前传的是 `trimmed.take(20)`）。

实测（2026-11-01，`10.10.0.211:8088`）：带该字段建会话、跑完一轮对话后，
`GET /api/chats` 里的名字从「帮我检查网络是否通」变成「一句话说明助手能力」。
不带该字段时名字不变。

生成是 `asyncio.create_task` 起的后台任务，不走推送、没有回调，
因此客户端只能刷新列表来发现。它还要先做一次模型调用，
超时由 `auto_title_config.timeout_seconds` 控制（默认 30 秒），
`finishRun` 里那一次立即刷新通常赶不上，需要按
「列表里的名字是否仍等于占位名」再补一次。这个判据由
`needsTitleRefresh` 实现，只补一轮，不轮询。

---

## 12、通知

QwenPaw 没有推送通道，客户端只实现**本地**通知。

触发条件：进程内收到 `completed` 帧，且 `AppVisibility.visibleCount == 0`
（即应用不在前台）时，发一条「回复已完成」通知。
应用在前台时不发，避免在用户正看着屏幕时打扰。

由此产生一个必然的边界：应用进程被系统杀掉后不会再有任何提醒。
草稿与历史仍可从服务端补齐，但「后台完成提醒」这个能力在进程存活之外无法提供。
这是放弃实时推送的代价，属于有意识的取舍而非缺陷。

---

## 12.2、服务端不提供的两类数据

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
轮询失败、存储操作、语音），与 QwenPaw 服务端日志分开记录。

日志只存在进程内的环形缓冲（上限 2000 条），默认关闭，关闭时所有写入是空操作。
导出格式固定为 `HH:mm:ss.SSS [类别] tag · 内容`，导入按同样格式解析，
无法识别的行原样保留为界面条目。敏感信息在写入前脱敏：
`Authorization: Bearer <token>` 与 URL 中的 `token=` / `access_token=` 一律替换为 `***`。

---

## 13、已知取舍

以下四项在项目范围内明确不做，且不是待办：

- **不做离线自动补发。** 断网时保留草稿与失败气泡，由用户点「重试」重新发送。
- **不做实时推送与常驻后台长连接。** 没有前台服务，也不做进程保活，进程被杀即失联。
- **不做 Mermaid 渲染。** 代码块降级为可复制的源码文本。
- **不做 L3 管理面**（智能体配置、技能管理、日志查看）。这类能力需要服务端管理 API 稳定后再评估。

服务端已提供而客户端尚未接入的能力另见 [待办清单.md](../待办清单.md)。

---

## 14、语音输入（离线）

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

## 15、包体体积与 ABI 取舍

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

## 16、MiSans 字体改为可选下载

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

---

## 17、跨版本适配：2.2.2.post1 与 2.3.0b2

服务端没有能力发现端口。`/api/capabilities`、`/api/features`、`/healthz` 一类路径
在两个版本上都不存在，`/api/status` 与 `/api/health` 返回 404。
唯一的版本信息来自 `GET /api/version`：

```json
{"version": "2.2.2.post1"}
{"version": "2.3.0b2"}
```

因此适配只有两条可用路径：**按版本号分支**，或**按状态码兜底**。
本节记录两个版本的实际差异，以及客户端据此定下的规则。

### 版本差了多少

以 `QWENPAW_OPENAPI_DOCS=1` 在临时 HOME 下起独立进程导出 `app.openapi()` 得到两份完整规格：

| 项目 | 2.2.2.post1 | 2.3.0b2 |
| --- | --- | --- |
| 路径数 | 517 | 545 |
| 新增路径 | — | 33 |
| 移除路径 | — | 5 |

客户端实际使用 **33 条路径**，逐条比对两份规格：

- 33 条的路径与 HTTP 方法在两版**完全一致**；
- 8 个关键端点的 `200` 响应 schema 完全相同；
- 只有两条端点的**参数**有差异，且都是新增可选参数，不影响既有调用：
  `POST /api/console/chat/stop` 多出 `initiator`（默认空串），
  `GET /api/models/{provider_id}/pool` 多出 `model_type`（可空）。
- 移除的 5 条里没有一条被客户端调用。

新增的 33 条集中在四块：插件管理（`config` / `enabled` / `unload` /
`repair-dependencies` / `dependency-upgrades`）、技能池下载任务、
ChatGPT 提供商 OAuth、`/api/runtime/*`，另有 `/api/advisor-mode`、
`/api/chats/resolve`、`/api/chats/{id}/decision-settings`。
这些都属于尚未接入的新能力，不需要为旧版本做降级——功能本身在 2.2.2 上不存在，
说明「当前服务端不支持」即可。

### 被移除的 5 条

| 路径 | 2.2.2.post1 | 2.3.0b2 |
| --- | --- | --- |
| `GET /api/workspace/download` | 200，流式 zip | 404 `Not Found` |
| `POST /api/workspace/upload` | 422（缺 `file` 字段） | 405 `Method Not Allowed` |
| `GET /api/agents/{agentId}/workspace/download` | 200 | 404 |
| `POST /api/agents/{agentId}/workspace/upload` | 422 | 405 |
| `POST /api/desktop/shutdown` | 404 | 404 |

注意后两条的**表现不同**：删掉的路由在 2.3.0b2 上返回 404，而方法不匹配返回 405。
`POST /api/workspace/upload` 之所以是 405 而不是 404，是因为同一路径下
`file-upload` 系的路由仍以其它方法存在，FastAPI 的路由表对这种情况回 405。
由此得出第一条判据：**405 不能当作「端点不存在」**，
而 404 也不能反推「端点被删」——路径拼错同样返回 404。

### 客户端据此定的规则

**不依赖任何可被移除的整包端点。** 需要整包能力时改走逐文件路径：

- 目录下载：`GET /api/workspace/tree` 按游标翻完，再对每个文件调
  `GET /api/workspace/file-download?path=&root=`。两个版本都支持，
  代价是多几次请求。`tree` 要翻到 `has_more` 为假为止，
  否则大目录会被静默截断（实测 `digest` 目录返回 `entries` 为空而 `has_more` 为假）。
- 整包上传：改用 `POST /api/workspace/file-upload`（多文件表单）或
  `POST /api/workspace/project-directory/upload-zip`（整目录 zip）。
  两者在两版都存在，字段形状也一致。

**不做运行时版本分支。** 理由有三条：

1. 客户端当前**没有**任何调用落在被删的 5 条上，分支没有作用对象。
2. 版本号不是稳定的能力标识：`2.3.0b2` 是预发布版的写法，正式版会是 `2.3.0`，
   靠字符串前缀比较迟早出错；而服务端不提供「这个版本有哪些端点」的接口。
3. 分支一旦引入就必须为每条分支维护一条回归路径，而两版共用的 33 条路径
   目前完全一致，分支只会增加未被覆盖的代码。

**遇到 404 时按上下文给出说明，而不是按版本猜测。** 现有做法已经符合这一条：
登录接口的 404 单独映射成「请求未落到登录端点」（见第 1 节），
其余 404 统一为「会话不存在或已被删除」。若将来接入某个只在 2.3.0b2 存在的能力，
调用方应就近给出「当前服务端版本不支持该功能」的文案，而不是让用户看到 404 原文。

### 字段层面的两处实际差异

路径一致不等于报文一致。实测两份容器返回的 JSON 有两处差别，都已按可空处理：

| 位置 | 2.2.2.post1 | 2.3.0b2 | 处理 |
| --- | --- | --- | --- |
| `GET /api/token-usage` 的 `cache_hit_rate` | 有调用时为数字 | 无调用时为 `null` | 声明为 `Double?`，界面显示「暂无数据」 |
| `GET /api/models/active` | 只有 `active_llm` | 多出 `default_models` | 忽略未知字段即可，无需建模 |

`cache_hit_rate` 一项要紧：OpenAPI 把它声明为 `number | null`，两版一致，
但 2.2.2 在有调用时恰好总是有值，此前把 DTO 写成非空 `Double` 一直没暴露。
在干净实例（2.3.0b2 容器首次启动）上拿到 `null` 后，整个响应解析失败，
界面报「加载用量失败」。这类「同一字段在空数据时才为 null」的形状
只能靠干净实例覆盖，用有数据的实例回归是测不出来的。

同理，`GET /api/agents/{agentId}` 的响应在 2.3.0b2 上多出 `advisor_mode` 整块、
少了 `tools.builtin_tools.ast_search` 条目；客户端只读 `approval_level`，
多余字段由 `ignoreUnknownKeys` 吸收，不需要处理。

---

## 18、服务端头像

四条路径在两个版本上都存在，形状一致，实测自 2.2.2.post1 与 2.3.0b2 两个容器：

| 方法与路径 | 作用 |
| --- | --- |
| `GET /api/profile/avatars` | 清单：`{"selected": id \| null, "history": [{id, mime, created_at}]}` |
| `GET /api/profile/avatars/{identifier}` | 图片字节，响应头 `Cache-Control: private, no-store` |
| `PUT /api/profile/avatars/selection` | 切换当前头像 |
| `POST /api/profile/avatars` | 上传新头像（multipart） |

**只有一个 owner，固定为 `"console"`。** `qwenpaw/user_assets/avatar_routes.py` 里
`avatar_owner()` 不读取任何请求参数，一律返回 `"console"`；服务端也没有按智能体
或按渠道区分的头像端点。因此客户端只能把这张图用于用户侧，智能体头像继续走本地存储。

服务端上限（`qwenpaw/user_assets/avatars.py`）：单张 2 MB，支持 PNG / JPEG / WEBP / GIF，
最长边 16384，历史保留 12 张。客户端不展示历史，只在 `selected` 变化时重新取图。

两个容器初次查询都返回 `{"selected":null,"history":[]}`，即默认未设置头像。
这条空数据形状是必须覆盖的：功能在「服务端没有头像」时不能改变任何现有表现。

**图片端点不能走字符串通道。** `QwenPawApi` 的常规请求把响应体读成 `String`，
二进制图会按字符集转换而损坏；为此单独开了 `requestBytes`，与 `execute` 同构，
只把 `body.string()` 换成 `body.bytes()`。

**不判定 `Cache-Control`，自己按 id 缓存。** 服务端对图片响应写了
`private, no-store`，意味着每次请求都会真的走网络；而客户端要在冷启动与断网时都能
显示上次的头像，因此把图片落到 `filesDir/server-avatar/{selectedId}.img`，
用 id 做文件名，`index.txt` 记当前选中项。id 未变时不重复下载（单张上限 2 MB）。

### 回归方法

```bash
# 清单：未设置头像时应为 {"selected":null,"history":[]}
curl -s http://10.10.0.211:8088/api/profile/avatars
curl -s http://10.10.0.220:8088/api/profile/avatars

# 稳定版
curl -s http://10.10.0.211:8088/api/version
# 预发布版
curl -s http://10.10.0.220:8088/api/version
```

动手改头像缓存逻辑时，注意「换 id 后旧文件必须被清掉」这条——服务端单张上限 2 MB，
攒几张就很可观，而旧 id 的图片在客户端没有任何用处。

### 通用回归方法

改协议相关代码后，对两个容器各跑一遍版本探测，再按需切设备侧场景：

设备侧用 `QWENPAW_HOST=10.10.0.220:8088 ./scripts/e2e_acceptance.sh --only A4`
切到预发布版跑单个场景。**不要在干净实例上跳过用量、列表这类「空数据」页面**，
上面那处 `null` 就只在干净实例上出现。

### 若将来必须放弃某项功能

判断标准是「在最新版本上是否仍有等价路径」：

1. 有等价端点（如整包下载 → 逐文件下载）：**改造，不放弃**。
2. 只有旧版本有、且无等价路径：**放弃该功能**，在本文与
   [待办清单.md](../待办清单.md) 对应条目里写明「2.3.0 起服务端已移除」，
   并注明最后一次可用版本，避免下一轮重新设计。
3. 只有新版本有、旧版本没有（如插件管理）：**接入，旧版本上按 404 说明不支持**，
   不做功能降级。「说明不支持」时给安装指引而不是「去启用」——插件被禁用后
   与未安装不可区分，见第 19 节。

---

## 19、插件探测

客户端需要判断服务端有没有装配套插件（id `qwenpaw-node-bridge`），并据此决定
功能入口是禁用还是走替代路径。本节记录 2026-11-03 在两个容器上的实测。

**id 与 HTTP 前缀是两段不同的字符串。** 插件 id 为 `qwenpaw-node-bridge`，
但插件路由的 prefix 取 `qwenpaw-node-bridge` 会得到
`/api/plugins/qwenpaw-node-bridge/...`，与核心 `plugins.py` 的
`/{plugin_id}/status`、`/{plugin_id}/files/{path}` 同层，容易撞车（见下文同名规则）。
两端约定插件统一用 prefix `/plugins/qwenpaw-node-bridge`，端点路径因此是
`GET /api/plugins/qwenpaw-node-bridge/info`，与 id 保持一致，不再出现缩写形式。

### 端点与两版差异

| 方法与路径 | 2.2.2.post1 | 2.3.0b2 |
| --- | --- | --- |
| `GET /api/plugins` | 200，数组 | 200，数组，多 `status` 与 `diagnostics` |
| `GET /api/plugins/{id}/status` | 200 `{id, loaded, enabled, version}` | 200，同上并多 `status` |
| `GET /api/plugins/{id}` | **404，该路由不存在** | 200 |
| `POST /api/plugins/{id}/enabled` | **不存在** | 存在，body `{enabled, interrupt_tasks}` |
| `GET /api/plugins/{id}/config` | 404（只有 PUT） | 404（只有 PUT） |
| `GET /api/plugins/{id}/enabled` | 404（只有 POST） | 404（只有 POST） |

列表元素形状（2.2 实测）：

```json
{"id":"qwenpaw-hub","name":"QwenPaw Hub","version":"1.0.0","description":"...",
 "author":"QwenPaw Team","enabled":true,"loaded":true,"plugin_type":"hub",
 "frontend_entry":null}
```

2.3.0b2 在同一形状上多出 `status: "active"` 与 `diagnostics: []`。
源码见 `qwenpaw/app/routers/plugins.py` 的 `list_plugins` 与 `get_plugin_status`。

### 判定规则

**以插件自己的 `GET /api/plugins/qwenpaw-node-bridge/info` 为准：200 即可用，404 即未检测到。**
不查核心的 `GET /api/plugins` 列表，理由如下。

1. **列表判定的两种麻烦，`/info` 都没有。** 查列表要区分 `enabled` 与 `loaded`：
   loader 尚未初始化时走磁盘扫描（`_list_plugins_from_disk`），返回
   `enabled: true, loaded: false`，把它当「已启用」会在启动竞态时误判；
   2.3 上还要再加 `status == "active"`。`/info` 返回 200 本身就说明该插件的
   router 已注册成功，比列表里的 `loaded: true` 证明力更强。
2. **不要用 `GET /api/plugins/{id}` 查单插件。** 该路由在 2.2.2.post1 不存在（404），
   2.3.0b2 才有（200）。用它做判定会让稳定版上所有插件都显示为未安装，
   而 2.2 恰恰是当前基线版本。
3. **不能比 `qwenpaw_version`。** 列表与 `/status` 响应都不含该字段，`plugin.json` 里的
   `qwenpaw_version.min` 只在安装校验时由服务端读取
   （`plugins/loader.py` 载入 manifest 后交给 `PluginApi`），不回传到列表。

**未注册的插件路径返回 404，不是 SPA 页面。** 本机 2.2.2.post1 实测
`GET /api/plugins/qwenpaw-node-bridge/info` → 404 `{"detail":"Not Found"}`；
`GET /api/plugins/qwenpaw-node-bridge/status` → 404 `Plugin 'qwenpaw-node-bridge' not found.`。
`_app.py` 的 SPA catch-all 里有 `if full_path.startswith("api/")` 直接抛 404 的分支，
因此客户端不必再判断响应体是不是 HTML。

**插件路由不在鉴权白名单里。** `auth.py` 的 `_PUBLIC_PATHS` 与 `_PUBLIC_PREFIXES`
都不含 `/api/plugins`，服务端开启登录后访问插件路由需要 token。探测因此只能排在
登录之后；验证阶段拿到 401 应记为「待重探」，不能当成未安装。

**插件路由与核心路由同名时命中核心的那条，已实测。** 插件 prefix 被 `registry.py`
拼成 `/api` + prefix，核心 `plugins.py` 在模块级先注册，插件路由随后插入。
在未安装插件的实例上请求同一前缀下的四个子路径，结果分成两类：

```
GET /api/plugins/qwenpaw-node-bridge/status    → 404 Plugin 'qwenpaw-node-bridge' not found.
GET /api/plugins/qwenpaw-node-bridge/files/x   → 404 Plugin 'qwenpaw-node-bridge' not found
GET /api/plugins/qwenpaw-node-bridge/info      → 404 {"detail":"Not Found"}
GET /api/plugins/qwenpaw-node-bridge/search    → 404 {"detail":"Not Found"}
```

前两条的参数化 detail 说明请求已进入核心 router；后两条连核心路由都没匹配上，
落到通用 404。因此 `/status` 与 `/files/**` 这两个子路径**不能给插件用**，
插件定义同名路由也收不到请求。插件只用 `/info`、`/search`、`/events` 这类子路径。

### 禁用与未安装不可区分

禁用插件的方式是把目录改名加 `.disabled` 后缀，发现阶段直接跳过
（`plugins/loader.py` 的 `_is_disabled_plugin_dir`）。实测：把 `clawhub-hub` 改名为
`clawhub-hub.disabled` 后，运行中的实例仍报告 `loaded: true`——已加载的实例在内存里，
要重启才生效；重启后列表只剩 3 个插件，`GET /api/plugins/clawhub-hub/status`
返回 404 `Plugin 'clawhub-hub' not found.`，与从未安装的表现完全相同。

因此客户端只设「已检测到」与「未检测到」两态。对未检测到的情况给安装指引，
**不要给「去启用」**——对 2.2 而言禁用的插件没有可用的启用开关，
把用户引过去只会看到一个不存在的入口。

### 插件能力自述

因为客户端拿不到版本约束，插件自己暴露能力清单：

```
GET /api/plugins/qwenpaw-node-bridge/info
{"plugin_version":"0.1.1","protocol_version":1,
 "server_version":"2.2.2.post1",
 "capabilities":["info","search"]}
```

客户端按 capability 名开关功能，不按插件版本推断。端点挂在插件自己的 router 上
（`api.register_http_router(prefix="/plugins/qwenpaw-node-bridge")`），返回 404 即视为不可用。
`protocol_version` 用于将来契约变更时拒绝不兼容的旧插件，客户端只接受 `1`。

`plugin_version` 由插件读 `api.manifest["version"]`，即 `plugin.json` 的 `version`，
不另存常量。`server_version` 由插件读 `qwenpaw.__version__`（该模块只有一行版本常量），
只用于排查两端配错版本，客户端不据此分支。两个版本号**都只用于展示与排查**。

**这条形状仍是两端约定的草案，尚未实现。** 判定以 `/info` 为准、404 的形状、
鉴权与路由冲突四条来自实测与源码；`/info` 的字段名与 capability 取值由本仓库
与插件仓库共同商定，落地前不属于「已实测」的内容。插件实现后需补齐一次真实响应
到本节，并把「草案」二字去掉。

### 回归方法

```bash
# 探测判定的标准对照：未装应 404，装好后应 200
curl -s -w " | %{http_code}\n" http://10.10.0.211:8088/api/plugins/qwenpaw-node-bridge/info
curl -s -w " | %{http_code}\n" http://10.10.0.220:8088/api/plugins/qwenpaw-node-bridge/info

# 核心列表与单插件状态仍可用于排查，两版都应返回 200
curl -s http://10.10.0.211:8088/api/plugins | head -c 200
curl -s -w " | %{http_code}\n" http://10.10.0.211:8088/api/plugins/qwenpaw-hub/status
```

测禁用态时改完目录必须重启服务端（`screen -S qwenpaw -X quit` 后重开），
否则内存里的实例仍报告已加载。测完把目录名改回去再重启一次。
---

## 20、分页的三种形态

服务端的列表类端点有三种分页写法，客户端的 `data/remote/Paging.kt` 把三者归一成
「取下一页 / 是否还有更多 / 追加到已有列表」，界面只看条目与 `hasMore`。

| 形态 | 代表端点 | 请求参数 | 响应字段 | 结束判据 |
| --- | --- | --- | --- | --- |
| `limit`/`offset` | `GET /api/console/inbox/events` | `limit`、`offset` | `{items, total}` | **无 `has_more`**；已取条数 ≥ `total` 即结束，`total` 缺失时以「取满一页」为准 |
| `cursor` | `GET /api/workspace/tree` | `cursor`、`limit` | `{entries, has_more, next_cursor}` | `has_more` 为布尔时以它为准；`has_more=false` 时即便带着 `next_cursor` 也一律结束 |
| 仅 `limit` | 备份、日志类 | `limit` | 裸数组或 `{items}` | 短页即结束（返回条数 < limit） |

三条公共护栏，写在 `settle()` 一处：

1. **空页且无游标视为结束。** 服务端已经给不出内容。
2. **游标与上一页相同视为结束。** 服务端没推进游标，继续请求只会死循环；
   本页已取到的条目保留，不因为游标缺陷丢数据。
3. **空白响应归为结束。** `QwenPawApi.decode` 对空 body 抛 `ApiException(204)`，
   真正的 HTTP 204 与「200 空 body」在这一层无法区分。干净实例上的「0 条事件」
   不该弹「服务端返回了空响应」，因此 204 按空页处理；其余异常照原样上抛。

`limit`/`offset` 端点**没有 `has_more`**，这一点容易按 cursor 形态写错：
按「有没有下一页字段」实现会永远判成结束，列表只显示第一页。

---

## 21、收件箱

服务端把 cron 执行、自动记忆写入一类的系统消息写进 `WORKING_DIR/inbox_events.json`
（上限 5000 条），由 console router 对外提供。以下形状在 2.2.2.post1 实测。

### 端点

| 方法 | 路径 | 作用 |
| --- | --- | --- |
| GET | `/api/console/inbox/events` | 事件列表，支持 `limit`/`offset`/`source_type`/`source_types`/`status`/`agent_id`/`unread_only` |
| POST | `/api/console/inbox/read` | 标记已读，body `{event_ids: [String], all: Boolean}` |
| DELETE | `/api/console/inbox/events/{event_id}` | 删除单条，不存在时 404 |
| GET | `/api/console/inbox/traces/{run_id}` | 该次运行的完整轨迹，不存在时 404 |

`limit` 默认 50、上限 500；`offset` 最小 0。

### 来源类型与筛选语义

`source_type` 只有五个取值，与 web 控制台「推送消息」页的选项一致：

| source_type | 写入位置 | 说明 |
| --- | --- | --- |
| `cron` | `app/crons/manager.py` | 定时任务结果、超时、投递失败 |
| `heartbeat` | `app/crons/heartbeat.py` | 心跳任务结果与超时 |
| `memory` | `agents/memory/reme_inbox.py` | 自动记忆写入 |
| `skill_autoupdate` | `app/routers/skills.py` | 技能自动更新，`agent_id` 固定为 `default` |
| `mail` | `app/mail/monitor.py` | 邮件自动处理 |

审批事件走 `/api/console/push-messages`，不写这五个来源。

**筛选后的计数。** `total` 与 `unread_count` 都是应用 `agent_id`、`status`、
`source_type(s)` 之后的数字；`unread_only=true` 只影响 `events`，`unread_count` 不变。
因此筛选生效时，顶栏的未读数是「筛选范围内的未读」，不是全局未读；
插件 SSE 推来的全局计数此时不能直接覆盖它。

**标记已读不分范围。** `POST /api/console/inbox/read` 带 `all: true` 会清除整库未读，
不接受来源或智能体条件。客户端因此在筛选生效时隐藏「全部已读」。

**定时任务不是自动全量写入。** `append_event(source_type="cron", event_type="cron_result")`
只在 `job.save_result_to_inbox` 为 true 时执行；该字段为 `null` 时按
`not (task_type == "text" and schedule.type == "cron")` 解析，即消息内容类周期任务默认不存。
普通执行失败不写事件，只有超时（`cron_timeout`）与投递失败（`cron_delivery_failed_fallback`）会写。

2026-11-08 在 `qp-test`（2.2.2.post1）实测：`source_type=memory` 得 3 条、
`source_type=cron` 得 1 条、`agent_id` 指向不存在的智能体得 0 条，
`total` 与 `unread_count` 随筛选同步变化。2.3.0b2 的参数一致性尚未回归。

### 响应形状

```json
{"events": [...], "total": 0, "unread_count": 0}
```

**没有 `has_more`。** `total` 与 `unread_count` 都是**过滤之后**的计数：
`unread_count` 在 `unread_only=true` 时与 `total` 相等，因此客户端不应在本地推算未读。

### 事件结构

```json
{"id": "uuid", "agent_id": "default", "source_type": "cron", "source_id": "",
 "event_type": "cron_result", "status": "success", "severity": "info",
 "title": "...", "body": "...", "payload": {}, "read": false,
 "created_at": 1793679546.0410624}
```

**`created_at` 是浮点秒**（服务端 `time.time()`），不是 ISO 字符串，也不是毫秒。
按 1000 乘后交 `Instant.ofEpochMilli` 格式化；时区必须显式传入，
否则本地通过、CI 失败。

轨迹形状：`{run_id, created_at, completed_at, status, meta, events[{at, event}], error?}`，
`events[].at` 同样是浮点秒，`event` 是服务端原样的任意结构。

### 删除的连带效果

删除事件时若该 `run_id` 不再被其它事件引用，服务端会同时删掉轨迹，
响应返回 `{deleted, trace_deleted, run_id}`。客户端不需要自己判断引用关系。

---

## 22、工具、环境变量与后台策略

三张配置表单的端点。以下形状在 2.2.2.post1 实测与源码确认。

### 工具

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/tools` | 返回**数组**，不是 `{tools: [...]}` |
| PATCH | `/api/tools/{name}/toggle` | **没有请求体**，服务端取反 |
| PATCH | `/api/tools/{name}/async-execution` | body `{async_execution: Boolean}` |
| GET | `/api/tools/{name}/config` | 返回裸配置字典 |
| POST | `/api/tools/{name}/config` | body `{"config": {...}}`，不是扁平字典 |

全部走 `X-Agent-Id` 归属。

工具字段：`name`、`enabled`、`source_plugin_id`、`source_plugin_name`、`category`、
`description`、`async_execution`、`icon`、`requires_config`、`config_fields`、`config_values`。

**两处 `null` 会让整份响应解析失败**（实测）：

```json
{"name": "read_file", ..., "category": null, "config_fields": null, "config_values": null}
```

无配置项的工具返回 `config_fields: null` 与 `config_values: null`，不是空数组/空对象；
非 select 类型的字段返回 `"options": null`。把任一字段声明成非空集合，
整份 `/api/tools` 反序列化失败，页面上表现为「没有可用的工具」——从响应看不出异常。

`config_values` 里的密码类字段已被服务端替换成 `***`。那是占位符不是真值，
原样提交会把密钥写成三个星号，客户端必须当作空处理。

**`toggle` 是取反，不是设置目标值。** 批量还原状态时若按「设为启用」的思路调用，
会把本就启用的工具逐个关掉；正确做法是先读当前状态，只对与目标不一致的项取反。
设置异步走 `/async-execution`，body 必须是 `{"async_execution": bool}`，
写成 `{"enabled": bool}` 会得到 422。

**`category` 只在插件工具上有意义。** 内置工具的 `category` 为 `null`，
`icon` 服务端下发的是 emoji（如 `🔧`）。官方 Console 的界面分组与图标都不依赖这两个字段：
它有一张 `TOOL_PRESENTATION` 静态表（`console/assets/toolPresentation-*.js`），
按 `name` 给出分组与一个 lucide 组件；只有 `source_plugin_id` 非空的工具才用 `category`，
且 `category` 不在 `files / web / media / agents / runtime / migration` 之列时归入 `other`。
客户端照此表实现，见 `docs/ui-guidelines.md` 9.16。

### 环境变量

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/envs` | 返回 `[{key, value}]`，**value 是明文** |
| GET | `/api/envs/catalog` | 规格元数据 |
| PATCH | `/api/envs` | 集合级，body 是扁平 `{"KEY": "value"}`，合并保存 |
| PUT | `/api/envs` | 整体替换 |
| DELETE | `/api/envs/{key}` | 只删自定义变量 |
| POST | `/api/envs/{key}/reset` | 内置变量恢复继承值 |

**`GET /api/envs` 在局域网内直接返回明文密钥。** 服务端只在落盘时加密、读取时透明解密，
当前实例 `auth.status.enabled=false` 时任何能连上的人都看得到。客户端默认遮罩，
点击才显示，离开页面恢复遮罩；遮罩长度固定，不跟随原值长度。

**`PATCH /api/envs` 是集合级，不是 `/{key}`。** body 是扁平字典，不是 `[{key, value}]` 数组。

**内置变量不能删除，只能重置。** 对 `catalog` 里出现过的 key 调 DELETE 返回
400 `Known variable must be reset`；客户端因此只给内置变量提供「恢复默认」。
`catalog` 里 `editable=false` 的项连编辑都不允许。实测对
`QWENPAW_LLM_MAX_RETRIES` 发 `PATCH` 返回 400 `Environment variable is read-only: …`。

**`catalog` 的元素形状**（`app/routers/envs.py:31-43`，2.2.2.post1 实测 17 项）：

| 字段 | 取值 | 说明 |
| --- | --- | --- |
| `effective_value` | 字符串 | 当前生效值：已配置取落盘值，否则取进程环境变量，再否则取默认值 |
| `source` | `user` / `system` / `default` | 与上一列的选择顺序一一对应 |
| `default` | 字符串 | 代码默认值 |
| `editable` | 布尔 | 等价于 `mutability == "hot_runtime"` |
| `mutability` | `hot_runtime` / `startup_only` | 17 项中只有 `QWENPAW_LLM_STREAM_FIRST_CONTENT_TIMEOUT` 与 `QWENPAW_LLM_STREAM_IDLE_TIMEOUT` 是 `hot_runtime` |
| `readonly_reason_code` | `startup` / `initial_default` / `agent_owned` / `null` | 来源 `envs/registry.py:112-136`；可热改项为 `null` |
| `configured` | 布尔 | 该 key 是否已落盘 |
| `description_key` | 字符串 | i18n 键名，服务端不提供译文正文 |

`startup_only` 的 15 项里，`QWENPAW_WORKING_DIR` 与 `QWENPAW_SECRET_DIR` 属于
`_PROTECTED_BOOTSTRAP_KEYS`（`envs/registry.py:181`）：`store.py:362` 在把落盘值注入环境时
会跳过它们，因此 `PATCH` 虽然能写进文件，重启后也不会覆盖启动时的值。
这两个目录必须先于配置加载确定，只能由启动命令的进程环境变量给出。

#### 新增环境变量

**没有 `POST /api/envs`。** 新增走 `PATCH /api/envs`：`store.py` 的 `update_env_vars`
是 `{**old, **updates}`，提交一个此前不存在的 key 即等于创建，未提交的已有项不受影响，
响应返回合并后的全量列表。用 `PUT` 会整体替换，省略的 key 会被删掉，不能用于新增。

`envs.py` 的 `_validate_updates` 与 `registry.py` 的校验依次为：

| 规则 | 实现位置 | 违反时 |
| --- | --- | --- |
| key 只含 `[A-Za-z0-9_]`，首字符不能是数字 | `registry.py:16` | 400 `Invalid environment variable name: {key}` |
| `QWENPAW_` 前缀必须全大写 | `registry.py:199-212` | 400 `QwenPaw environment variable must be uppercase: {key}` |
| `editable=false` 的内置项 | `registry.py:199-212` | 400 `Environment variable is read-only: {key}` |
| 与已有项大小写不敏感重名 | `envs.py:52-71` | 400 `Environment variable names conflict: {a}, {b}` |
| 内置 number / float 项的值格式 | `registry.py:215-230` | 400 `Invalid float value for {key}` 等 |

未知 key 与 `value_type == "string"` 的项**不校验内容**，key 与 value 都没有长度上限。
提交前服务端只对 key 做 `strip()`，value 原样保存。

客户端在本地做前四条校验，措辞按中文给出；本地拦不住的（例如新增的 key 恰好是
内置数值型变量、值格式不符）仍然提交，由服务端的 `detail` 显示回页面。
只读判定排在重名判定之前：`catalog` 列出全部 17 个内置项（含未配置的），
先查重会让只读项得到「已存在」这句无用提示。

**未配置的内置可写项允许新增**，这是覆盖其默认值的唯一入口；
已配置的内置项仍按重名拦下，应去编辑原值。

### 后台策略

| 方法 | 路径 | body |
| --- | --- | --- |
| GET | `/api/settings/offload-policy` | 返回 `{"default_action": "keep_foreground"}` |
| PUT | `/api/settings/offload-policy` | `{"default_action": "keep_foreground" | "offload"}` |

只有这一个字段，取值经正则限定为两个。写入后服务端会热更
`coordinator.offload_on_deadline`，不需要重启。

---

## 23、定时任务与工具调用控制

v0.2.0 接入的两组端点。以下形状在 2.2.2.post1 上实测与源码确认。

### 定时任务

路由在 `qwenpaw/app/crons/api.py`（前缀 `/cron`），同时以 `/api/cron/*` 与
`/api/agents/{agentId}/cron/*` 两种方式挂载。客户端统一走全局路径加 `X-Agent-Id` 头
（`QwenPawApi.baseBuilder` 已注入），不拼 agent 段。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/cron/jobs` | 返回 `list[CronJobSpec]`，**不含运行状态** |
| GET | `/api/cron/jobs/{id}` | `CronJobView`（`spec` + `state`） |
| POST | `/api/cron/jobs` | 新建，body 为 `CronJobSpec` |
| PUT | `/api/cron/jobs/{id}` | **整体替换** |
| DELETE | `/api/cron/jobs/{id}` | 返回 `{"deleted":true}` |
| POST | `/api/cron/jobs/{id}/pause` \| `/resume` \| `/run` | 返回 `{"paused"|"resumed"|"started":true}` |
| POST | `/api/cron/jobs/{id}/promote` | 返回 `CronJobSpec` |
| GET | `/api/cron/jobs/{id}/state` | `CronJobState` |
| GET | `/api/cron/jobs/{id}/history` | `list[CronExecutionRecord]`，按时间倒序 |
| GET | `/api/cron/dispatch-targets` | `{channels, items}`，支持 `channel` / `keyword` / `limit` |

**新建时 `id` 由服务端生成**，客户端传了也会被忽略；`PUT` 时 `spec.id` 与路径不符返回 400。
`GET /cron/jobs` 不含状态，状态只在 `/state` 与详情里，因此列表需要二次逐条拉取。

**`meta` 与 `request` 必须原样带回。** `request` 是 `extra="allow"` 模型，
`meta` 里带迁移审阅标记。`PUT` 是整体替换，客户端若只序列化自己认识的字段，
编辑一次就会抹掉审阅锁与模型覆盖。客户端因此两者都用 `JsonObject` 持有，编辑时只改已知键。
实测：给任务写入 `meta.model_override` 与 `request.model` / `request.provider_id` 后，
从客户端编辑并保存一次，四个未知键全部保留。

**`save_result_to_inbox` 为 null 时由服务端计算**（`not (text && cron)`），客户端不发这个键。
Kotlin 侧用 `explicitNulls = false` 保证序列化结果里没有该键。

**`timezone` 服务端默认 UTC**，客户端必须显式传。客户端不做国际化，一律按
`Asia/Shanghai`（UTC+8）创建与展示，不读设备时区，也不跟随服务端：
跟随设备会让同一份任务在不同设备上得到不同表达式，而服务端的 UTC 默认会让任务在凌晨触发。

**`silent` 只对 agent 任务有效**，text 任务带 silent 会被 422 拒绝。

**服务端会把 `dow` 归一化成三字母缩写。** 客户端发 `0 9 * * 1,3`，
`POST` 的响应里是 `0 9 * * mon,wed`（`app/crons/models.py::_crontab_dow_to_name`）。
读取自己创建的任务时必须两种写法都认，否则每次编辑都会退化成「直接编辑」。
`cron` 支持 5 段与可归一化的 4 段、3 段写法，6 段（带秒）返回 422。

**409 有两种含义**：导入任务未审核（`PermissionError: Imported cron job requires explicit
promotion`）与状态冲突。前者要展示「需先确认」并给「审核通过」入口，不能统一报「操作失败」。

**`promote` 之后任务仍是禁用状态。** 实测 `promote` 的响应 `enabled=false`，
`meta.portability.requires_review` 变成 false，但顶层 `meta.safety` 仍保留
`disabled_until_explicit_promotion`。判定是否待审核只能看 `portability.requires_review`。

| `CronJobSpec` 字段 | 说明 |
| --- | --- |
| `id`、`name`、`enabled` | `id` 由服务端生成 |
| `schedule` | `type` 取 `cron` / `once`；`timezone` 客户端恒为 `Asia/Shanghai` |
| `task_type` | `text` / `agent` |
| `text` | `task_type=text` 时必填 |
| `request` | `task_type=agent` 时必填，`extra="allow"` |
| `dispatch` | `{type: "channel", channel, target: {user_id, session_id}, mode, silent}` |
| `save_result_to_inbox` | 为 null 时服务端计算 |
| `runtime` | `max_concurrency=1`、`timeout_seconds=120`、`misfire_grace_seconds=600`、`share_session=false`、`tool_safety=false` |
| `meta` | 任意字典 |

**服务端时区对照用 `GET /api/config/user-timezone`。** 返回 `{"timezone": "<IANA 名>"}`，
只读不改。不一致时客户端把同一触发时刻在两个时区下并排列出。
已保存任务的时间取服务端返回的 `next_run_at`（含偏移，如 `2026-11-04T09:00:00+08:00`），
不在本地重算；表单里未保存的草稿才在本地按 `Asia/Shanghai` 推算。

### 工具调用控制

路由 `qwenpaw/app/routers/tool_calls.py`（前缀 `/tool-calls`），按 `session_id` 定位，不按 agent。

| 方法 | 路径 | 状态码 |
| --- | --- | --- |
| GET | `/api/tool-calls/{session_id}` | 返回 `{items, total}` |
| GET | `/api/tool-calls/{session_id}/{id}` | `ToolCallInfo` |
| POST | `/api/tool-calls/{session_id}/{id}/cancel` | 202，body `{force: Boolean}` |
| POST | `/api/tool-calls/{session_id}/{id}/offload` | 202，**没有请求体** |
| POST | `/api/tool-calls/{session_id}/{id}/extend-deadline` | 202 |
| GET | `/api/tool-calls/{session_id}/{id}/output` | `{tool_call_id, is_closed, final_state, content}` |
| GET | `/api/tool-calls/{session_id}/{id}/stream` | SSE |

**`extend-deadline` 的 body 是 `{seconds?, no_deadline, target}`。**
`target=offload` 只能用于 running；`target=kill` 对 running 与 offloaded 都可用，
但超过 `max_internal_timeout_secs` 会被拒绝。成功响应带回最新的
`offload_remaining` / `kill_remaining` 快照。

**失败分三档**：404 表示条目不存在或已过缓存期，409 表示状态已变化
（实测对已结束条目取消返回 409 `Cannot cancel`）。

**`started_at` 是 monotonic 秒，不是 epoch**，只能用于对照 `elapsed`，不能当时间戳显示。
`offload_remaining` / `kill_remaining` 是取回时的快照，界面倒计时需要自己按已过时间递减。

**`/stream` 没有 `event:` 行、没有重放。** 只发
`data: {"type":"chunk","data":{...}}` 与结尾的 `data: {"type":"done"}`；
晚订阅只能拿到 `done`。因此实时输出只是加分项，`/output` 才是权威值。

**完成后的条目只在缓存里保留约 60 秒**，之后一律 404。

**`tool_call_id` 就是聊天 SSE 里 `plugin_call` 的 `call_id`**，客户端 `ToolCard.callId`
已经持有，不需要另加字段。

---

## 24、配套插件端点

配套插件（`QwenPaw-Node-Bridge` 仓库，id `qwenpaw-node-bridge`）只补核心没有的能力。
两端之间只有「路径 + 响应形状」这一层契约，版本号各自编号。

### 自述端点

```
GET /api/plugins/qwenpaw-node-bridge/info
```

```json
{"plugin_version":"0.1.3","protocol_version":1,
 "server_version":"2.2.2.post1","capabilities":["info","search","sse"]}
```

未安装返回 **404**；服务端开启登录而请求没带 token 返回 **401**。
两者含义完全不同：前者是「没装」，后者是「现在问不了」。

### 会话全文检索端点

```
GET /api/plugins/qwenpaw-node-bridge/search?q=<关键词>&limit=20&cursor=<偏移量>
```

```json
{"items":[{"session_id":"mobile-70d0c538","msg_id":"0b1f485e…","role":"assistant",
  "created_at":"2026-11-01T01:21:49","snippet":"…[sleep]…","score":-3.42}],
 "next_cursor":20,"total":31}
```

`q` 为空白时返回 **400**；`limit` 默认 20、上限 100；`cursor` 是**偏移量**而不是记录 id，
因为 `seq` 只在单个 `history.db` 内唯一。

两条取值规则都由实测确定，都与直觉相反：

1. **`session_id` 是 `history.db` 的 `session_id` 列，不是 `/api/chats` 的对话 `id`。**
   两者在 `mobile-*` 形态下恰好一一对应，但 Web 端的历史会话会出现一个
   `session_id` 挂多个对话（实测 `1793496108871-ciozfnb` 对应三个对话）。
   拿它直接请求 `/api/chats/{id}` 会返回 404 并让客户端误判为断线，必须先到对话列表换出 `id`。
2. **`msg_id` 取 `dedup_key`（即消息对象的 `metadata.original_id`），不能用消息 `id`。**
   `GET /api/chats/{id}` 每次请求都会重新生成消息 `id`，同一段内容两次请求拿到的值不同，
   只有 `original_id` 稳定。滚动定位按它匹配。

检索走两路：含 CJK 的查询用 `LIKE` 并在插件侧截取片段，其余用 FTS5 的 `snippet()` 与
`bm25()`（`bm25` 返回负值，插件取负后作为 `score`）。
`conversation_history_fts` 的 `tokenize='porter unicode61'` 不支持中文分词，
连续汉字会被当作单个 token，FTS 对中文查询无效。

消息范围限定 `kind in ('context_msg','model_turn')`。`history.db` 的 `kind` 只有
`context_msg`（用户消息）、`model_turn`（助手正文）、`tool_result`（工具输出）三种，
**没有 `kind='message'`**——那是 API 响应里的 `type` 字段值，照它写条件一条也查不到。
`tool_result` 体积大且检索价值低，排除。

命中片段用 `[` `]` 包裹，客户端把方括号渲染成高亮色，不原样显示。

### 事件流端点

```
GET /api/plugins/qwenpaw-node-bridge/events
Accept: text/event-stream
```

```
event: ready
data: {"protocol_version":1}

event: inbox
data: {"unread_count":3}

event: approval
data: {"pending":1}
```

连接建立后先发 `ready`，随后各发一次 `inbox` 与 `approval` 的当前值，此后只在数值变化时推送。
插件每 2.5 秒在连接内部轮询一次 `inbox_store.query_events(limit=1)` 的未读数与
`approvals.get_approval_service()._pending` 的条数，客户端断开即退出生成器。

**只推这两类，不含 `cron`。** 定时任务的 `CronManager` 按 workspace 隔离，
取摘要需要请求上下文里的智能体标识，而插件的事件生成器里没有 `Request`，
拿不到当前 workspace。客户端因此保留原有的定时任务轮询，事件流只覆盖收件箱与审批。

### 客户端判定的三态

| 判定 | 条件 | 界面 |
| --- | --- | --- |
| 已检测到 | 200 且 `protocol_version == 1` | 已检测到 QwenPaw Node Bridge v{plugin_version} |
| 未检测到 | 404，或 200 但 `protocol_version != 1` | 未检测到配套插件 |
| 尚未检测 | 401 / 403，或连不上且此前无结论 | 尚未检测 |

**`protocol_version` 更大时按「未检测到」处理**：契约已变更，旧客户端不该继续使用
该插件的端点。功能开关一律看 `capabilities` 里的名字，不比对 `plugin_version`——
靠版本号推断能力会让两端各维护一张对照表，迟早对不上。

**没有「已禁用」这一态。** 禁用插件是把目录改名为 `.disabled`，发现阶段直接跳过，
重启后列表里消失、端点返回 404，与从未安装完全一致。因此界面不提供「去启用」。

### 插件侧的路径约束

核心 `plugins.py` 已经注册了同前缀下的 `/status` 与 `/files` 下任意子路径，
且**先于插件 router 匹配**：请求 `/api/plugins/qwenpaw-node-bridge/status` 时
核心会返回 `{"detail":"Plugin 'qwenpaw-node-bridge' not found."}` 的 404，
请求根本到不了插件。可安全使用的子路径是 `/info`、`/search`、`/events`。

三条加载器约束（`qwenpaw/plugins/loader.py` 与 `architecture.py` 核对）：

1. 后端入口必须导出名为 `plugin` 的对象并实现 `register(api)`；
2. `register_http_router(router, prefix=…)` 的 prefix 不能是 `/`，也不能与其他插件重复；
3. 只用核心没有的子路径。

### 安装与校验

`qwenpaw plugin validate` 的参数是**目录**而不是 zip：源码里按
`plugin_path / "plugin.json"` 读取清单，对 zip 执行会报 `plugin.json not found`。
打包后的 zip 只能在 `plugin install` 时使用。

---

## 25、运行中历史、分组默认值与交付文件形状

本节记录 2026-11-07 一轮修复中实测到的服务端行为，几条都会直接影响客户端取值。

### 25.1 运行中的 `GET /api/chats/{id}` 不完整

生成过程中请求会话详情，返回的 `messages` **只包含已落库的轮次**：
本轮用户消息与正在生成的正文都不在里面，`status` 为 `running`。

实测（2.2.2.post1）：发出消息后立即取详情，`messages` 条数与刷新前一致，
最新一条仍是上一轮的回答；生成结束后再取，本轮的问与答才出现。

因此客户端不能把 `/api/chats/{id}` 当作「当前界面应该显示的全部内容」：
刷新、切走再返回、进程重开后，若直接用服务端历史覆盖界面，本轮用户消息会消失。
正确做法是**以服务端历史为底，再拼本地仍在生成的那一段**，
并把本地尾部截到最近一条用户消息（见 [ui-guidelines](ui-guidelines.md) 3 节）。

### 25.2 分组的默认值

`GET /api/chats/groups` 里，内置分组之一满足 `kind == "default"`，
其 `id` 为 `default`，`name` 为 `Uncategorized`（客户端显示「未分组」）。
另外两个内置分组的 `kind` 是 `scheduled` 与 `subagent`。

把一个会话移回默认分组时，请求必须写默认分组的 `id`：

```json
PUT /api/chats/{id}
{"group_id": "default"}
```

写成 `{"group_id": null}` 会被拒绝，返回 **400 `Unknown chat group: None`**。
客户端原先在「未分组」选项里发 `null`，界面没有报错（错误被 `runCatching` 吞掉），
表现是「点了没反应」。默认分组的 id 应从 `GET /api/chats/groups` 取，取不到时才退回字面量
`default`。

**客户端不再合成前置的「未分组」选项。** 服务端已经返回 `kind == "default"` 的条目，
再合成一个会让对话框出现两个「未分组」。移动对话框显式过滤 `kind == "default"`，
用自己的前置项表示「未分组」。

### 25.3 `send_file_to_user` 的两种输出形状

同一个工具，按文件格式给出两种不同的 `output`：

| 格式 | `source` | 内容 |
| --- | --- | --- |
| png | `{"type":"base64","media_type":"image/png","data":"…"}` | 字节内联在 JSON 里 |
| webp | `{"type":"url","url":"file:///tmp/bridgetest.webp"}` | 只有服务端本地路径 |

webp 这种没有内联字节，客户端要自己把 `file://` 绝对路径换成预览端点
`GET /api/files/preview/{path}` 才能显示缩略图。**不能按 `media_type` 判断是不是图片**：
webp 这一条会把 `media_type` 标成 `application/octet-stream`，只能按文件名扩展名判断。

实测：卡片正常出现，缩略图由预览端点渲染，点击可全屏预览。

### 25.4 服务端 keep-alive 是 5 秒

用同一个 TCP 连接连续发两次 `GET /api/version`，中间空闲：

| 空闲 | 结果 |
| --- | --- |
| 2 秒 | 复用成功，200 |
| 4 秒 | 复用成功，200 |
| 8 秒 | `RemoteDisconnected`，对端已关闭连接 |

这对应 uvicorn 的默认 `--timeout-keep-alive 5`。

**因此「验证连接」这类低频探测不能复用连接池。** 两次验证的间隔通常远超 5 秒，
池里的连接早已被服务端关闭；复用它只会读到 EOF，把「地址是通的」误报成「网络中断」。
探测客户端改为不保留连接（`ConnectionPool(0, 1, NANOSECONDS)`），每次重新握手。
实测：同一实例间隔 60 秒连续验证两次，均返回「连接正常，服务端 2.2.2.post1，QwenPaw Node Bridge v0.1.0」。

### 25.5 验证连接时的插件探测需要 token

`GET /api/plugins/qwenpaw-node-bridge/info` 在服务端开启登录且请求没带 token 时返回 401。
因此：

- **编辑已有实例**时，验证连接带上已保存的 `token`，能读到 `plugin_version`（实测 `v0.1.0`）；
- **新增实例**（填表添加、引导式第 1 步）此时还没有 token，401 只能显示「配套插件状态未能确认」；
  安装情况在保存后由设置页「配套插件」行给出。
