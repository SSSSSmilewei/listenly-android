# 拾听 3.0 同步协议（v1）

客户端（`android/`）与同步服务器（`server/`）之间的唯一约定。任何一方改动接口都必须先改这份文档。

## 设计原则

- **离线优先**：客户端本地数据库是事实来源，服务器只是中转与备份。不登录时不发出任何请求。
- **只同步学习记录**：答案、进度、收藏、完成记录、活跃统计、可选的外观设置。**不上传音频和材料文件**。
- **记录级“后写者胜”（LWW）**：每条记录带混合逻辑时钟 `clock` 与 `device`，按 `(clock, device)` 字典序比较，大者胜。
- **计数类数据不合并、按设备拆分**：活跃统计的键里含设备 ID，每台设备只写自己的那一行，总量由客户端求和。因此 LWW 永不丢计数。
- **一次往返**：`POST /v1/sync` 同时推送本地变更并拉取远端变更。

## 记录（Record）

```json
{
  "collection": "answer",
  "key": "cet6-example/section-a-1/q1",
  "value": {"choice": "B", "submitted": true},
  "deleted": false,
  "clock": 117482455040000001,
  "device": "d_7f3a9c2e4b1d"
}
```

| 字段 | 类型 | 规则 |
|---|---|---|
| `collection` | string | 只允许：`answer` `position` `bookmark` `completion` `activity` `setting` |
| `key` | string | 1–256 字节 UTF-8，不含控制字符 |
| `value` | object 或 null | 序列化后 ≤ 16 KiB；`deleted=true` 时应为 null，服务器存 null |
| `deleted` | bool | 墓碑。删除也是一次写入，参与 LWW |
| `clock` | int64 | 混合逻辑时钟：`(unixMillis << 16) \| counter`，必须 > 0，且其毫秒部分不得晚于服务器当前时间 24 小时以上（否则 `bad_clock`），防止异常时钟永久锁死一条记录 |
| `device` | string | 客户端设备 ID；**服务器以令牌里的设备 ID 覆盖它**，客户端填的值被忽略 |
| `seq` | int64 | 仅出现在服务器返回中：该用户范围内单调递增的序号 |

### 各集合的键与值（客户端约定，服务器不解析 `value`）

| collection | key | value |
|---|---|---|
| `answer` | `{paperId}/{articleId}/{questionId}` | `{"choice":"B","submitted":true}` |
| `position` | `{paperId}` | `{"seconds":18.4,"articleId":"morning"}` |
| `bookmark` | `{paperId}/{articleId}` | `{}`；取消收藏 = `deleted:true` |
| `completion` | `{paperId}/{articleId}` | `{"completedAt":"2026-10-22T08:00:00Z","correct":3,"total":3}` |
| `activity` | `{yyyy-MM-dd}/{deviceId}` | `{"listenedSeconds":600,"playSeconds":720,"studySeconds":900,"questions":5,"articles":1}`（`listenedSeconds` 为完整听完篇章计入的分钟口径，`playSeconds` 为实际播放时长） |
| `setting` | `{name}`，如 `accent`、`darkMode`、`speed` | `{"v":"amber"}` |

## 认证

- 访问令牌：JWT（HS256），有效期 15 分钟，载荷含 `sub`（用户 ID）、`did`（设备 ID）、`exp`、`iat`。
- 刷新令牌：256 位随机串，服务器只存 SHA-256；有效期 30 天；**每次刷新轮换**。
  - **宽限期**：刚被轮换掉的那一个旧令牌，在轮换后 60 秒内再次使用，视为客户端因网络丢包而重试，再签发一对新令牌（同时作废上一次签发的那对），不算泄露。
  - 超过宽限期、或使用更早的旧令牌，视为泄露，吊销该设备的全部令牌。
- 密码：Argon2id（m=64MiB, t=1, p=4）加盐存储。
- 所有需要认证的接口使用 `Authorization: Bearer <accessToken>`。

## 接口

所有请求与响应均为 `application/json; charset=utf-8`。请求体上限 1 MiB。

### `POST /v1/auth/register`

```json
{"username": "listener", "password": "至少8位", "deviceName": "Xiaomi 15"}
```

→ `201`

```json
{
  "userId": "u_…",
  "deviceId": "d_…",
  "accessToken": "…",
  "accessExpiresAt": "2026-10-22T08:15:00Z",
  "refreshToken": "…",
  "refreshExpiresAt": "2026-11-21T08:00:00Z"
}
```

- `username`：3–32 位，`[a-z0-9_.-]`，大小写不敏感（存小写）。
- `password`：8–128 字节。
- `deviceName`：1–64 字符。
- 用户名已存在 → `409 username_taken`；关闭注册 → `403 registration_closed`。

### `POST /v1/auth/login`

请求同注册。每次登录创建一个新设备。→ `200`，响应同注册。错误的用户名或密码统一返回 `401 invalid_credentials`。

### `POST /v1/auth/refresh`

```json
{"refreshToken": "…"}
```

→ `200`，响应同注册（`deviceId` 不变）。无效或过期 → `401 invalid_refresh_token`。

### `POST /v1/auth/logout`（需认证）

吊销当前设备的所有刷新令牌并删除该设备。→ `204`

### `GET /v1/me`（需认证）

```json
{
  "userId": "u_…",
  "username": "listener",
  "devices": [
    {"id": "d_…", "name": "Xiaomi 15", "createdAt": "…", "lastSeenAt": "…", "current": true}
  ]
}
```

### `DELETE /v1/devices/{id}`（需认证）

移除本账号下的另一台设备（吊销其令牌）。不存在或不属于本人 → `404 not_found`。→ `204`

### `DELETE /v1/me`（需认证）

永久删除账号及其全部记录。请求体：`{"password":"…"}`，密码错误 → `401 invalid_credentials`。→ `204`

### `POST /v1/sync`（需认证）

```json
{
  "cursor": 0,
  "changes": [ /* Record，最多 500 条 */ ]
}
```

服务器逐条处理 `changes`：

1. 校验失败的记录放进 `rejected`，不影响其他记录。
2. 与已存记录比较 `(clock, device)`，严格更大才写入，并分配新的 `seq`；否则忽略（计入 `ignored`）。
3. 写入在同一个数据库事务内完成。

然后返回该用户 `seq > cursor` 的记录，按 `seq` 升序，最多 1000 条：

```json
{
  "accepted": 12,
  "ignored": 1,
  "rejected": [{"index": 3, "reason": "value_too_large"}],
  "changes": [ /* Record，含 seq */ ],
  "cursor": 4521,
  "hasMore": false
}
```

- `cursor` 是本次返回最后一条的 `seq`；没有返回记录时等于请求的 `cursor`（若请求的 cursor 大于服务器最大 seq，则返回服务器最大 seq）。
- `hasMore=true` 时客户端应带新 `cursor`、空 `changes` 继续拉取。
- 返回中包含本设备刚写入的记录；客户端按 LWW 应用是幂等的。
- `rejected[].reason`：`bad_collection` `bad_key` `bad_clock` `value_too_large` `bad_value`。
- `changes` 超过 500 条 → `400 too_many_changes`。

### `GET /healthz`

→ `200 {"status":"ok"}`，不需认证。

## 错误格式

```json
{"error": {"code": "invalid_credentials", "message": "用户名或密码错误"}}
```

| HTTP | code |
|---|---|
| 400 | `bad_request` `too_many_changes` `weak_password` `bad_username` |
| 401 | `unauthorized` `invalid_credentials` `invalid_refresh_token` |
| 403 | `registration_closed` |
| 404 | `not_found` |
| 409 | `username_taken` |
| 413 | `body_too_large` |
| 429 | `rate_limited`（带 `Retry-After` 头） |
| 500 | `internal` |

## 速率限制

- 认证接口（register / login / refresh）：每个 IP 每分钟 10 次，令牌桶。
- `/v1/sync`：每个用户每分钟 60 次。

## 服务器配置（环境变量）

| 变量 | 默认值 | 说明 |
|---|---|---|
| `LISTENLY_ADDR` | `:8080` | 监听地址 |
| `LISTENLY_DB` | `./data/listenly.db` | SQLite 文件路径 |
| `LISTENLY_JWT_SECRET` | 无 | ≥ 32 字节；未设置时自动生成并保存到数据库目录下的 `jwt.secret`（权限 0600） |
| `LISTENLY_ALLOW_REGISTRATION` | `true` | 自建实例可关闭注册 |
| `LISTENLY_TRUST_PROXY` | `false` | 为 true 时从 `X-Forwarded-For` 取客户端 IP（用于限流） |
| `LISTENLY_LOG_LEVEL` | `info` | `debug` `info` `warn` `error` |
