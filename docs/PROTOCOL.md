# DSH 原生客户端通信协议（M1 逆向 + 实测验证）

> 已用 Node 原生 TCP/HTTP 实连 `http://192.168.x.x:3081` 验证通过。
> 这是构建原生 Android DSH 客户端（无 WebView）的通信层蓝图。

## 0. 总览

DSH Web UI 与后端通过两条通道通信，原生客户端复刻这两条即可：

| 通道 | 用途 | 传输 | 鉴权 |
|---|---|---|---|
| **HTTP RPC（unary）** | 一次性调用：列会话、建会话、发消息、取状态、读文件元数据 | `POST /api/<endpoint>` | 同源 cookie |
| **WebSocket 流（streaming）** | 持续事件流：agent 回复、工具调用、文件变更 | `ws://host/api/remote.mux` | 同源 cookie |

所有帧/报文均为 **JSON 文本**，无二进制 typert 编码（除附件响应可能是 multipart/form-data）。

## 1. 鉴权（两枚 cookie）

1. `POST /pocket-login`，body `token=<PIN>`（form-urlencoded），PIN=`你自己配置的访问密码`。
   - 响应 `302` → `Location: /?dsh-pocket-auth=1`，`Set-Cookie: dsh_pocket_token=<hex>; HttpOnly; SameSite=Lax; Path=/; Max-Age=2592000`。
2. `GET /`（带 dsh_pocket_token）。
   - 响应 `200`（DSH UI HTML），`Set-Cookie: dsh-auth-<随机后缀>=<jwt>`。
3. 之后所有 `/api/*` 与 WS 握手都带这两个 cookie：`Cookie: dsh_pocket_token=…; dsh-auth-…=…`。
   - 缺 dsh-auth → `401`。缺 dsh_pocket_token → 登录页。

> 注意：`?token=<PIN>` 直链方式对 `/api/file` 等 `/api/*` 路由 **不** 鉴权（401），必须走上面的两步拿双 cookie。

## 2. HTTP RPC（unary 调用）

- **URL**：`POST /api/<endpoint>`，其中 endpoint = `<namespace>/<method>`，如 `session/list`、`session/prompt`、`workspaceFiles/read`。
- **Header**：`Content-Type: application/json`；`Cookie: <双 cookie>`。
- **请求体**（client-request 信封）：
  ```json
  {"type":"client-request","rpcId":"<uuid>","method":"session/list","payload":{"args":{"_request":{}}}}
  ```
  - `rpcId`：任意 uuid（UUIDv4），响应原样回传用于配对。
  - `method`：与 URL 里的 endpoint 相同。
  - `payload.args.<wire>`：每个参数按其 `wire` 键放入 args。**request 参数的 wire 键是 `_request`**（下划线前缀，不是 `request`）。
- **响应**（server-response 信封）：
  ```json
  {"type":"server-response","rpcId":"<同 rpcId>","result":{"ok":true,"value":<结果>} }
  ```
  失败：`result:{ok:false,error:{code,message,details}}`。
  - Content-Type 可能是 `application/json`（普通）或 `multipart/form-data`（含二进制附件，如 session/attachment）。

### 关键 unary 端点（namespace/method，参数 wire=_request）

| endpoint | _request 字段 | 用途 |
|---|---|---|
| `session/list` | `{cursor?:string}` | 列会话；value.items[] 含 sessionId/updatedAt/running/cwd/projections(turnOutline,title,goal,tokenUsage,inbox,sessionStats) |
| `session/create` | `{workspaceId?,cwd?,sessionId?,agentPreset?}` | 建会话 |
| `session/control` | `{sessionId}` | 取会话完整状态（baseline+projections） |
| `session/prompt` | `{requestId,sessionId,mode:"queue"|"steer",content:[{type:"text",text}|{type:"image",mediaType,data}|{type:"file",receiptId}],clientTimeZone?}` | **发消息** |
| `session/updateQueue` | `{sessionId,itemId,action:{kind:"edit"|"remove"|"steer",...}}` | 编辑/删除排队项 |
| `session/cancel` | `{sessionId}` | 取消 |
| `session/projections` | `{sessionId,...}` | 投影 |
| `skills/list` | `{...}` | 技能目录 |
| `workspaceFiles/read` | `{path,offset?,limit?}` | 读文本文件 |
| `workspaceFiles/readBytes` | `{path,range?}` | 读字节（base64? multipart?） |
| `workspaceFiles/list` | `{path}` | 列目录 |

> requestId / sessionId / itemId / workspaceId 等是 branded string（schema 为 `z.intersection(z.string(), z.unknown())`），线上就是普通字符串。

### 文件字节路由（非 RPC，直接 GET）
`GET /api/file?path=<URL编码的绝对路径>`，带双 cookie → 返回字节 + 正确 MIME（已实测返回 APK 2.41MB）。
有 maxBytes 上限。PhoneFileOpener 已用此路由实现"文件在手机打开"。

## 3. WebSocket 流（事件流 / streaming 调用）

- **URL**：`ws://<host>:<port>/api/remote.mux`，握手带 `Cookie: <双 cookie>` + 标准 WS 头（Upgrade/Connection/Sec-WebSocket-Key/Version=13）。响应 `101 Switching Protocols`。
- **多路复用**：一条 WS 上开多条逻辑流，每条用唯一 `streamId`（uuid）区分。
- **客户端→服务端帧**（JSON 文本）：
  - `{"type":"open","streamId":"<uuid>","endpoint":"<endpoint>","payload":{"args":{...}}}` — 开流（endpoint 如 `$events`、`session/follow` 等 streaming 方法）
  - `{"type":"item","streamId":"<uuid>","value":<json>?}` — 上行一项（可选 value）
  - `{"type":"end","streamId":"<uuid>"}` — 上行结束
  - `{"type":"cancel","streamId":"<uuid>"}` — 取消
- **服务端→客户端帧**：
  - `{"type":"item","streamId":"<uuid>","value":<json>?}` — 下行一项（流式结果/事件）
  - `{"type":"end","streamId":"<uuid>"}` — 流结束
  - `{"type":"error","streamId":"<uuid>","error":{"code","message","details"}}` — 流失败
- **客户端帧必须 mask**（WS 标准）；服务端帧不 mask。

### `$events` 事件流（gateway 内部端点）
- open：`{"type":"open","streamId":"<uuid>","endpoint":"$events","payload":{"args":{}}}`
- 首个 item：`{"value":{"type":"ready","clientId":"<uuid>","host":{"home":"C:\\Users\\xiao"}}}` — 拿到 clientId。
- 之后 agent 事件（turn 增量、工具调用、文件变更等）作为 item 持续推送到该 streamId。
- `$events/result` 是 unary（HTTP RPC）端点，回送单个事件结果。

> **unary 方法不能走 WS 流**（会报 `gateway/signature-invalid: unary Remote methods cannot be opened through the stream carrier`）。unary 必须走 HTTP RPC；streaming（`$events`、`session/follow` 等）走 WS。

## 4. MVP 原生客户端调用序列

1. 登录：POST /pocket-login(token) → cookie1；GET / → cookie2。
2. 开 `$events` WS 流 → 拿 clientId（持久，接收 agent 事件）。
3. `session/list`（HTTP RPC）→ 取会话列表 + turnOutline（对话历史）。
4. 选/建会话（`session/control` 或 `session/create`）。
5. `session/prompt`（HTTP RPC，mode:"queue", content:[{type:"text",text:"…"}]）→ 排队用户消息。
6. agent 运行 → 事件经 `$events` WS 流推送（item 帧）→ 原生渲染（turn 增量/工具调用/最终文本）。
7. 文件打开：`GET /api/file?path=` + 双 cookie → ACTION_VIEW（PhoneFileOpener）。

## 5. 事件类型（v2.0 实测）

$events 推送的 item.value 是 Cordis 事件，实测只含状态/心跳，不含消息正文：

- `{"type":"ready","clientId":"<uuid>","host":{"home":"C:\\Users\\xiao"}}` — 首帧。
- `{"type":"emit","event":"api-session/status","args":["<sessionId>", true|false]}` — 会话运行/空闲（true=运行中）。
- `{"type":"emit","event":"api-session/activity","args":["<sessionId>", <epochMs>]}` — 心跳。

> 关键：agent 回复正文不在 $events 里，而在 session/projections 的 turnOutline（每轮 prompt+response，全文不截断）。原生聊天 MVP 渲染模型 = session/list/projections 取对话 + session/prompt 发消息 + $events 的 status 信号触发轮询 projections 取新轮（或定时轮询）。token 级流式留待 M3 探查 session/follow。

## 6. 端点 wire-key 对照表（v2.0 实测更正）

> 重要更正：args 里的 wire key 每个端点不同，不能统一用 request。下表为 face descriptor 实读 + 实连验证：

| 端点 | args wire key | 参数对象字段 |
|------|--------------|-------------|
| session/list | _request | {cursor?} |
| session/create | request | {workspaceId?, cwd?, sessionId?, agentPreset?} |
| session/prompt | request | {requestId, sessionId, mode:"queue"|"steer", content:[...], clientTimeZone?} |
| session/projections | request | {sessionId} -> {asOfSeq, values:{title, goal, tokenUsage, contextPressure, contextBreakdown, inbox:{next-turn,next-step}, sessionStats, turnOutline:[{turn,seq,prompt,response}]}} |
| session/cancel / fork / page / rename / search / selectModel / updateQueue / attachment / openWorkspacePath / workspacePathApplications | request | 见 face def |
| skills/list | request | {} |
| session/control / canOpenWorkspacePath / initializeDefaultModel / modelCatalog | （无参数，args={}） | — |
| fileReferences/list | agentId + query | 见 face def |
| workspaceFiles/list | workspaceFileScopeId + path | scopeId=SessionId，path=绝对路径 -> {path, entries:[{name, type:"file"|"directory"|"other", size?}], truncated} |

session/prompt.content 元素：{type:"text", text} | {type:"image", mediaType, data, name?} | {type:"file", receiptId}。
session/prompt 返回 {accepted: true}（排队成功，非最终回复）。

## 7. v2.0 原生 MVP 实现映射

- 登录：DshClient.login()（POST /pocket-login + GET /）。
- 会话列表：session/list（_request）-> SessionItem[]（含 turnOutline）。
- 聊天：进会话取 session/projections（request）-> 渲染 turnOutline；发消息 session/prompt（request）-> 轮询 projections 直到新轮出现。
- 文件打开：workspaceFiles/list（workspaceFileScopeId + path）浏览 -> GET /api/file?path= 双 cookie 取字节 -> FileProvider + ACTION_VIEW（PhoneFileOpener，cookie 取自 DshClient.cookieHeader()）。

## 8. v3+ 待办

token 级流式（探查 session/follow streaming 或 control 轮询）；Markdown/代码/diff 渲染；deliverables 卡片打开；forks/subagents；workspace 文件浏览器完善；重连/通知/持久化。

## 9. session/follow 流式端点（M3 升级路径，已实采）

session/follow 是 mode:'stream' 端点，可在 WS 开流，实时推送会话事件（替代 projections 轮询）。

- 请求（wire=request，边界校验严格）：{address:{kind:"session", sessionId}, assistantStream?:true, maxMessages?:number, turnWindow?:{minMessages,minTurns}}
  - 注意：address 嵌套对象，不是裸 sessionId（裸 sessionId 会 boundary-validation 失败）。
  - assistantStream:true 仍只推一条完整 assistant/message（远程网关不暴露 token 级增量）。
- 开流：WS open {type:"open", streamId, endpoint:"session/follow", payload:{args:{request:{address:{kind:"session",sessionId}}}}}。
- 帧（item.value）= SessionFollowFrame 联合：{type:"event", event:{type,seq,time,data}} 或 {type:"snapshot",...}（初始历史快照）。

实测 event.type 序列（一次 prompt）：
  agent/inbox/spliced（用户消息入队/出队）→ turn/start{turn} → step/start{turn,step} → system/message{message:{role,content}} → user/message{content:[{type:text,text}],role,source,id}（×N，含 system-reminder 注入）→ request/header{header:{config,tools}} → request/context{provider,model,contextWindow} → session/title → assistant/message{turn,step,message:{role:"assistant",content:[{type:"reasoning",text},{type:"text",text}]}} → step/end{turn,step} → turn/end{turn,reason:{kind:"completed"}}。

渲染要点：
- assistant/message.message.content 含 {type:"reasoning"}（思考）与 {type:"text"}（正文）部分；正文部分即要显示的回复。
- step 完成（assistant/message 到达）即显示完整回复，无需轮询；turn/end 标记一轮结束。
- 历史会话开流应先收 snapshot 帧（初始消息），再收增量 event；maxMessages/turnWindow 控制回看范围（待 M3 验证 snapshot 形状）。

## 10. 产出文件（deliverables）机制调研（待桌面恢复后验证）

DSH 无独立 deliverable 端点；产出文件 = fileReferences（present 工具创建文件引用）。

- fileReferences/list：wire = agentId + query（query 为字符串/对象）。
- 包：dsh-file-reference（schema）、dsh-api-remotes/lib/client.js（face 注册）。
- agentId 是否 = sessionId、返回 shape（含文件路径？）待桌面恢复后实测（本轮 GET / 返回 503，无 dsh-auth，所有 /api/* 401，无法验证）。
- 聊天内打开产出文件的目标：follow 流或 fileReferences/list 拿到产出文件路径 → PhoneFileOpener.openOnPhone(path, name)（/api/file 取字节 + ACTION_VIEW，已验证）。

## 11. 运行依赖与当前服务状态

- app 依赖 DSH 桌面宿主在线：pocket-login（302）由网关处理；GET / 由桌面宿主服务并下发 dsh-auth JWT。桌面离线时 GET / 返回 503（HTML 错误页），无 dsh-auth → 所有 /api/* 与 WS 均 401/unauthorized。
- 本轮（第 10 轮）实测：pocket-login=302 正常，GET /=503（3 次重试一致）→ 桌面宿主当前离线。此前第 1-9 轮桌面在线时全部协议验证通过。
- 结论：app 代码已完成并经早期实连验证；桌面离线期间无法实连验证或真机测试，需用户启动 DSH 桌面后恢复。

## 12. 端点勘误与最终验证（第 12 轮，重要）

§11 的「桌面离线」为误判。真实根因：**公网映射 IP 的 NAT/代理破坏了登录 cookie 握手**。

- http://192.168.x.x:3081（公网映射）：pocket-login 302 正常，但 GET /?dsh-pocket-auth=1 返回 503（DSH Pocket 错误页「无法完成登录握手：浏览器没保存会话 cookie，代理反复重试未成功」），拿不到 dsh-auth → 所有 /api/* 与 WS 全 401。
- http://192.168.x.x:3081（局域网直连）：pocket-login 302 → GET /?dsh-pocket-auth=1 200 + dsh-auth → 全部正常。历史会话标题佐证：「192.168.x.x:3081 可以进入，映射到外网后显示密码错误」。
- 结论：app 须用 LAN IP（或其它直连/HTTPS 入口），公网裸 IP 不可用。v2.6 起 SERVER_URL 默认改为 LAN IP，且登录页地址/密码可编辑并持久化（SharedPreferences），用户可自行切换入口而无需重建。

### 第 12 轮 LAN 端点完整 E2E 验证（全通过）

### 第 16 轮 停止/中断协议（session/cancel）+ 事件词汇表

**停止当前轮次 = `session/cancel`（unary direct，非流）**——不是 session/control。
来源：`@deepseek-ai/dsh-api-session-controller/lib/typert.host.js` 端点注册：
`{ id:'#session/cancel', service:'sessionController', namespace:'session', method:'cancel', invocation:{kind:'direct'} }`，
参数 schema：`{ request: { sessionId: string } }`，结果 schema：`{ accepted: true }`。

Wire（活体验证通过）：
```
POST /api/session/cancel   Cookie: dsh_pocket_token=…; dsh-auth-…=…
{type:"client-request", rpcId, method:"session/cancel", payload:{args:{request:{sessionId}}}}
→ {type:"server-response", rpcId, result:{ok:true, value:{accepted:true}}}
```
活体流程：建会话→prompt 启动一轮→（1.5s 后）session/cancel→follow 流随即出现 `turn/end`（seq 增长），取消生效。

**关于 session/control**：它本身也是真实端点，但是 **流端点（mode:'stream'）**——
"stream Remote methods must be opened"，即必须经 WS open 帧打开，不能 POST 单发。
用途是客户端"重连控制流"（client Session state + reconnecting control stream），**不是停止**。

**session/follow 完整事件词汇表**（snapshot.records[] 与 live `item.value.event` 同构）：
`turn/start` · `step/start` · `step/end` · `turn/end` ·
`assistant/attempt` · `assistant/message`（content part 数组：reasoning/text/tool-call）·
`user/message` · `system/message` · `request/header` · `request/context` ·
`session/title` · `session/title-llm-request` · `agent/inbox/spliced`。
app 现在把 turn/start→"开始回复"、step/start→"执行步骤"、assistant/attempt→"正在生成回复…"、
request/context|header→"准备上下文"、session/title→"生成标题" 显示为活动状态行。

**其他已发现端点（来自 typert 注册，待实现）**：
`session/steer`（中途转向当前轮；mode:"steer" 或 steer/edit/remove 排队项；当前轮未运行则 `session/steer-unavailable`）、
`session/updateQueue`（编辑/移除/steer 排队消息）、`session/title`、`session/flush`、
`fileReferences/list`、`workspaceFiles/list`、`canOpenWorkspacePath`、`skills/list`。

login(dsh-auth) OK | session/list(24) OK | session/projections(29 轮) OK | session/follow WS(快照 cursor=2117, records=316) OK | workspaceFiles/list(cwd, 58 条) OK | GET /api/file(200, 650B) OK

## 13. 工作区 / 归档 / 历史翻页（第 17 轮实测，v2.13 依此实现）

### 13.1 归档状态不在 session/list 里

`session/list` 的 item 字段只有：`sessionId / updatedAt / agentAvailable / running / blank / cwd / projections`。
**没有 archived 字段**。归档集合只存在于工作区流。

### 13.2 workspace/follow（WS 流，args 必须是空对象）

- HTTP 调用会报 `gateway/signature-invalid: stream Remote methods must be opened through the stream carrier`，必须走 WS。
- 开流：`{"type":"open","streamId":..,"endpoint":"workspace/follow","payload":{"args":{}}}`
- 首帧：`{"type":"baseline","value":{"items":[{"workspaceId","path","title","sessionIds":[...],"createdAt","updatedAt"}],"archivedSessionIds":[...],"pinnedSessionIds":[...]}}`
- 实测：2 个工作区（Work / default-workspace），archivedSessionIds 20 个，pinnedSessionIds 空。
- 权威落地文件：`C:\Users\xiao\.dsh\storages\workspace.json`（global.archivedSessionIds + tables.workspaces）。

### 13.3 归档 / 取消归档（unary）

| 端点 | wire | 参数 | 返回 |
|---|---|---|---|
| `workspace/archiveSession` | request | {sessionId} | {archivedSessionIds:[...]} |
| `workspace/unarchiveSession` | request | {sessionId} | {archivedSessionIds:[...]} |

两者都返回**完整的最新归档集合**，可据此立即本地更新，不必等流推增量。
不存在的 sessionId：archiveSession 报 `session/not-found`；unarchiveSession 返回 ok。

### 13.4 孤儿会话（重要）

`session/list` 28 条 vs `workspace/follow` items 的 sessionIds 并集 19 条 → **9 条不属于任何工作区**
（都是 cwd = `C:\Users\xiao\.dsh\profiles\desktop` 的会话）。客户端必须有「未分组」兜底桶，否则这些会话会凭空消失。

### 13.5 session/follow 快照形状

```json
{"type":"snapshot",
 "header":{"version","id","createdAt","cwd","isSeeded","delegationDepth","agentPreset"},
 "cursor":3159,
 "records":[{"type":"event","event":{"type","seq","time","data"}}],
 "hasMore":true,
 "projections":{"asOfSeq","values":{"title",...}}}
```

- records **已按 seq 升序**；实测 259 条，覆盖 turn 48–68（≈最近 21 轮），`hasMore=true` 表示还有更早的。
- 单帧约 630KB，体积主要来自 `assistant/message.data.stream`（token 级 chunk 明细），解析时可忽略该字段。
- `turnWindow` / `maxMessages` 加在 request 里会被拒（`gateway/input-invalid: wire field "request" failed boundary validation`），**follow 不支持控制历史量**。

### 13.6 session/page：历史翻页（「加载更早」的正解）

- unary，wire=`request`：`{address:{kind:"session",sessionId}, throughSeq, beforeSeq?, maxMessages?}`
- 返回 `{records:[{type:"event","event":{...}}], hasMore}`（records 结构与 snapshot 完全同构）
- `throughSeq` 是上界（开区间）：传当前最早已加载的 seq 即拿到更早的一批。
- 实测：`throughSeq=2600, maxMessages=100` → 643 条 records，seq 1957–2599；
  `maxMessages=300` → 1932 条，seq 669–2600。注意 maxMessages 是**回溯事件条数上限**，不是轮数。

### 13.7 事件词汇与配对（实测）

- 事件类型（259 条统计）：assistant/message 27 / step/start 26 / step/end 27 / turn/start 20 / turn/end 21 /
  user/message 23 / system/message 2 / **tool/call 5** / **tool/result 5** / tool/ptc-dispatch(-start) 32 /
  agent/inbox/spliced 44 / request/header 11 / request/context 2 / deliverables/presented 2 /
  workspace/changes 2 / goal/change 6 / model/selection 2 / command/run 1 / command/done 1。
- `assistant/message.data = {turn, step, message:{role, content:[part]}, usage, stream}`，part 三种：
  `{type:"reasoning",text}` / `{type:"text",text}` / `{type:"tool-call",id,name,arguments}`。
- **同一个 (turn,step) 只会有一条 assistant/message**；一个 turn 可以有多个 step（实测 turn48→2 个、turn67→3 个），
  正文需要按 step 升序拼接，只保留第一个 step 会丢内容。
- **工具 ID 三者完全一致**：`tool-call part.id` == `tool/call.data.callId` == `tool/result.data.message.toolCallId`
  （形如 `call_xxx|fc_call_xxx`），可放心用 callId 配对。实测 5/5 全部配到结果。
- `user/message.data.source.kind` 三种：`user`（真人）/ `goal`（目标驱动自动提示）/ `model-selection`（切模型通知）。
  user/message **不带 turn 号**，需等后续 `turn/start` 才能绑到具体轮次。

### 13.8 历史思考/工具可还原（v2.13 修复的根因）

旧实现把 snapshot 只取 `cursor`、records 整包丢弃，且用 `seq <= baseline` 过滤掉所有历史事件，
导致历史轮次的 reasoning / toolCalls 恒为空。实测按 records 归约后：
**21/21 轮都有思考内容**（最长 24629 字符），**5/5 工具调用配到结果**（参数 2248–4529 字符，旧代码截断到 120）。

### 13.9 文本权威性

`session/projections` 的 turnOutline 覆盖全部 68 轮，但 response 可能是摘要
（实测 turn48：projections 119 字符 vs 快照 214 字符）→ **快照文本优先**，projections 仅作首屏兜底。

## 14. 会话操作 / 目标 / 产出文件（第 18 轮实测）

### 14.1 端点可用性

| 端点 | wire | 参数 | 状态 |
|---|---|---|---|
| `session/search` | request | {query} | **本部署被禁用**：`session search is disabled: this deployment configures the session-query index with openAt "never"` → 搜索只能做客户端本地过滤 |
| `session/rename` | request | {sessionId, title} | 可用（不存在的 id 报 session/not-found） |
| `session/fork` | request | {sessionId, atSeq?} | 可用 |
| `session/create` | request | {workspaceId?, cwd?, sessionId?, agentPreset?} | 可用；**workspaceId 会被校验**（非法值报 workspace/not-found） |

### 14.2 会话目标（goal）

- `session/projections.values.goal` **实测常为 null**，不能作为目标来源。
- 真实来源：`goal/change` 事件，`data = {kind, version, operation, goal:{id, revision, objective, phase, maxGoalRounds, blockedReason}}`。
  取 `data.goal.objective`，**取最后一个**（同一会话会多次变更，实测 6 条事件 / 4 个非空 objective）。

### 14.3 产出文件（deliverables）

`deliverables/presented` 事件：`data = {turn, callId, files:[{path, description}]}`。
- `path` **可能是相对路径**（实测 `DshPocket-2.11.apk`、`harness-2.12.apk`），需拼上会话 cwd。
- cwd 来源：`session/follow` 快照的 `header.cwd`（实测 `C:\Users\xiao\Documents\Work`）。
- 拼好后走 `GET /api/file?path=` + FileProvider + ACTION_VIEW（PhoneFileOpener）。

### 14.4 上下文压力

`session/projections.values.contextPressure = {pressureTokens, projectedTokens, contextWindow}`。
实测 121087 / 121157 / 256000 → 顶栏显示「上下文 47% · 118K/250K」。
同级的 `sessionStats = {turns, steps, llmMs, toolMs, ttftMs, ttftSteps, decodeMs, decodeTokens}` 也可展示。
