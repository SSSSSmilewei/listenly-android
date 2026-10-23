# 拾听 Listenly 同步服务器

## 这是什么

拾听（Listenly）是一个**离线优先**的英语听力 App：不联网、不登录也能完整使用。

这个同步服务器是**可选的**。只有当你想在多台设备（例如手机和平板）之间同步学习记录时才需要它。它只同步这些内容：

- 答题记录、播放进度、收藏、完成记录
- 每天的学习时长统计
- 可选的外观设置（主题色、深色模式、播放速度等）

它**不会**上传任何音频或材料文件。所有数据保存在服务器上的**一个文件**里（SQLite 数据库），备份和迁移都只需要复制文件。

服务器占用很小：镜像约 24 MB，空闲时内存几十 MB，一台最便宜的云服务器或家里的 NAS / 树莓派都能运行。

---

## 一条命令部署（Docker）

前提：服务器上已安装 [Docker](https://docs.docker.com/engine/install/)（包含 `docker compose`）。

1. 把本目录（`server/`）复制到服务器上。
2. 在该目录下执行：

   ```bash
   docker compose up -d --build
   ```

3. 检查是否正常运行：

   ```bash
   curl http://127.0.0.1:8080/healthz
   # 应返回 {"status":"ok"}
   ```

就这么简单。数据保存在名为 `listenly-data` 的 Docker 数据卷中（容器内路径 `/data`），重启、升级容器都不会丢失。

常用命令：

| 想做什么 | 命令 |
|---|---|
| 查看日志 | `docker compose logs -f` |
| 停止 | `docker compose down` |
| 升级到新版本 | 替换代码后再执行 `docker compose up -d --build` |
| 查看健康状态 | `docker ps`（STATUS 一栏会显示 `healthy`） |

> 默认配置只在本机 `127.0.0.1:8080` 上监听。要让手机通过互联网访问，请按下文「配置 HTTPS」用反向代理对外提供服务。**不要在没有 HTTPS 的情况下把服务暴露到公网**，否则密码和登录凭证会以明文传输。

---

## 配置（环境变量）

在 `docker-compose.yml` 的 `environment:` 部分修改，改完执行 `docker compose up -d` 生效。

| 变量 | 默认值 | 说明 |
|---|---|---|
| `LISTENLY_ADDR` | `:8080` | 监听地址 |
| `LISTENLY_DB` | `./data/listenly.db`（Docker 镜像中为 `/data/listenly.db`） | 数据库文件路径 |
| `LISTENLY_JWT_SECRET` | 无 | 签发登录凭证用的密钥，至少 32 字节。不设置时会自动生成并保存在数据库同目录下的 `jwt.secret`（权限 0600）。**一般不用设置。** |
| `LISTENLY_ALLOW_REGISTRATION` | `true` | 是否允许新用户注册。自己和家人的账号注册完之后，建议改成 `false` |
| `LISTENLY_TRUST_PROXY` | `false` | 放在反向代理（Caddy / Nginx）后面时设为 `true`，这样限流才能识别真实的客户端 IP。**直接对外暴露时必须保持 `false`**，否则别人可以伪造 IP 绕过限流 |
| `LISTENLY_LOG_LEVEL` | `info` | 日志级别：`debug` `info` `warn` `error` |

日志里**不会**出现密码、登录凭证或任何学习记录内容。

---

## 备份与恢复

所有数据都在数据卷里的两个文件中：

- `listenly.db` —— 数据库（账号、设备、学习记录）
- `jwt.secret` —— 登录凭证密钥（丢了也没关系，只是所有设备需要重新登录）

### 推荐：在线备份（不用停服务）

```bash
docker compose exec listenly /listenly-server backup /data/backup-$(date +%F).db
docker compose cp listenly:/data/backup-$(date +%F).db ./
```

这会生成一个完整、一致的单文件快照，可以随时执行，适合放进每天的定时任务（cron）。目标文件已存在时会报错，不会覆盖。快照会留在数据卷里；镜像里没有 shell，如需清理旧快照，可用下面「停机复制」里的 busybox 方式删除。

### 简单方式：停机复制

```bash
docker compose stop
docker run --rm -v listenly-data:/data -v "$PWD":/backup busybox \
  sh -c 'cp /data/listenly.db* /data/jwt.secret /backup/'
docker compose start
```

> 服务器运行时直接复制 `listenly.db` **可能拿到不完整的数据**（数据库使用 WAL 模式，最近的写入可能还在 `listenly.db-wal` 中）。请用上面两种方式之一。

### 恢复

```bash
docker compose stop
docker run --rm -v listenly-data:/data -v "$PWD":/backup busybox \
  sh -c 'rm -f /data/listenly.db-wal /data/listenly.db-shm && cp /backup/backup-2026-10-22.db /data/listenly.db && chown 65532:65532 /data/listenly.db'
docker compose start
```

---

## 配置 HTTPS（推荐用 Caddy）

App 与服务器之间必须使用 HTTPS。最省事的办法是用 [Caddy](https://caddyserver.com/)，它会自动申请和续期免费证书。

前提：你有一个域名（例如 `sync.example.com`），并已把它解析到服务器的 IP，服务器的 80 和 443 端口对外开放。

1. 安装 Caddy（参见官网），然后把 `/etc/caddy/Caddyfile` 改成：

   ```
   sync.example.com {
       reverse_proxy 127.0.0.1:8080
   }
   ```

2. 在 `docker-compose.yml` 里把 `LISTENLY_TRUST_PROXY` 改成 `"true"`，执行 `docker compose up -d`。
3. 重载 Caddy：`sudo systemctl reload caddy`。
4. 在 App 里把服务器地址填成 `https://sync.example.com`。

使用 Nginx 时，关键配置是：

```nginx
location / {
    proxy_pass http://127.0.0.1:8080;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header Host $host;
    client_max_body_size 2m;
}
```

> 服务器只信任 `X-Forwarded-For` 中**最右边**的地址（也就是你的反向代理亲眼看到的地址）。请只使用一层反向代理；如果前面还有 CDN，需要让 CDN 之后的那一层代理把真实 IP 写进该头。

---

## 常见问题

**忘记密码怎么办？** 目前没有找回密码的功能（服务器不收集邮箱）。可以在 App 中退出登录，用新用户名重新注册；本地数据不受影响，会重新上传。

**两台设备同时修改了同一道题？** 以“最后修改”的那次为准（按记录上的时间戳比较）。学习时长按设备分开记录、再由 App 求和，所以不会丢失。

**怎么删除账号？** 在 App 中使用“删除账号”，需要再次输入密码。服务器会永久删除该账号的所有记录。

**限流是什么？** 为防止暴力破解，每个 IP 每分钟最多 10 次登录/注册/刷新凭证；每个账号每分钟最多 60 次同步。超出时返回 429，并告诉客户端多少秒后再试。

---

## 开发者文档

### 接口约定

唯一权威的接口规范是 [`../docs/v3/sync-protocol.md`](../docs/v3/sync-protocol.md)。改接口之前必须先改那份文档。

### 技术栈

- Go 1.26，只用标准库 `net/http`（带方法的路由模式，如 `POST /v1/sync`）、`log/slog`、`database/sql`
- `modernc.org/sqlite`：纯 Go 的 SQLite，无需 cgo，可编译为静态二进制
- `golang.org/x/crypto/argon2`：Argon2id 密码哈希（m=64MiB, t=1, p=4）
- `github.com/golang-jwt/jwt/v5`：HS256 访问令牌。选用成熟库而非手写，是为了避免 `alg` 混淆、时间校验等细节上的错误；解析时只允许 `HS256`，且强制要求 `exp`
- 无 ORM：纯 SQL，迁移文件用 `embed` 内嵌、按版本号顺序执行

### 目录结构

```
server/
├── cmd/listenly-server/main.go   # 入口：配置、优雅退出、healthcheck / backup 子命令
├── internal/
│   ├── config/      # 读取 LISTENLY_* 环境变量，生成 jwt.secret
│   ├── store/       # SQLite 连接、内嵌迁移（migrations/*.sql）、Repo（纯 SQL）
│   ├── auth/        # Argon2id、JWT、刷新令牌轮换与重用检测、账号/设备
│   ├── syncsvc/     # 记录校验、LWW 合并、游标分页
│   ├── httpapi/     # 路由、处理器、中间件（请求 ID、恢复、访问日志、限流、认证、1 MiB 请求体上限）
│   └── ratelimit/   # 按键的令牌桶
├── Dockerfile
└── docker-compose.yml
```

分层：`httpapi`（协议/HTTP）→ `auth`、`syncsvc`（业务规则）→ `store`（SQL）。业务层不知道 HTTP，存储层不知道业务规则（LWW 判定在 `syncsvc.Wins`）。

### 关键实现说明

- **数据库**：WAL 模式、`busy_timeout=10s`、外键开启、`synchronous=NORMAL`。写连接池只有 1 个连接，事务以 `BEGIN IMMEDIATE` 开始，所有写入在进程内串行，避免 `SQLITE_BUSY`；读使用独立的多连接池。
- **LWW**：按 `(clock, device)` 字典序比较，严格更大才写入；`device` 一律取令牌里的设备 ID。被接受的写入在同一事务内分配该用户递增的 `seq`。
- **刷新令牌**：只存 SHA-256。每次刷新把旧令牌标记为已轮换并记录其后继（`replaced_by`），旧令牌保留到原过期时间用于检测重用。
  - **60 秒宽限期**：刚被轮换掉的那一个令牌（其后继尚未被轮换）在轮换后 60 秒内再次出现，视为客户端没收到响应而重试：删除上一次签发的刷新令牌、签发新的一对，并通过设备的 `min_access_gen` 让之前签发的访问令牌全部失效（访问令牌带 `gen` 声明）。窗口从最初那次轮换算起，重试不会延长窗口。同一设备的写事务串行执行，所以并发重试最终只留下一个有效的后继。
  - 超过 60 秒，或使用更早的祖先令牌 → 视为泄露，吊销该设备（删除全部刷新令牌并标记设备为已吊销；每次认证都会检查设备状态，因此已签发的访问令牌也立即失效）。
- **时钟上限**：记录 `clock` 的毫秒部分（`clock >> 16`）比服务器当前时间晚超过 24 小时，拒绝为 `bad_clock`，防止异常时钟永久锁死一条记录。
- **Argon2 并发**：同时进行的哈希计算最多 `min(4, CPU 数)` 个，防止并发登录耗尽内存；用户名不存在时也会计算一次哈希，避免通过响应时间探测用户名。
- **后台清理**：每分钟清理空闲的限流桶，每小时删除过期的刷新令牌。

### 运行测试

本机不需要安装 Go，用 Docker 即可：

```bash
docker run --rm -v "$PWD":/src:Z -v listenly-gomod:/go/pkg/mod -w /src golang:1.26 \
  sh -c 'gofmt -l . && go vet ./... && go test -race ./...'
```

（`:Z` 用于 SELinux 系统，例如 Fedora；其他系统可以去掉。）

测试覆盖：LWW 排序（同 clock 按设备打破平局、墓碑、旧写入被忽略、批内重复键）、每一种 `rejected.reason`（含时钟上限的边界：恰好 24 小时通过、多 1 毫秒拒绝）、游标/`hasMore` 分页、刷新令牌轮换与重用检测（宽限期内重试、刚超出宽限期、更早的祖先令牌、宽限期内并发重试只留一个有效后继）、限流与 `Retry-After`、`X-Forwarded-For` 处理、请求体上限，以及一个完整的端到端 HTTP 流程（注册 → 设备 A 推送 → 设备 B 登录拉取 → 冲突编辑 → 退出登录 → 删除账号），并检查日志中不含密码、令牌与记录内容。

### 本地运行（不用 Docker 镜像）

```bash
docker run --rm -it -p 8080:8080 -v "$PWD":/src:Z -v listenly-gomod:/go/pkg/mod -w /src golang:1.26 \
  go run ./cmd/listenly-server
```

### 添加数据库迁移

在 `internal/store/migrations/` 下新增 `NNNN_描述.sql`（版本号递增，例如 `0002_add_x.sql`）。启动时会自动按顺序执行尚未执行的迁移，每个迁移在一个事务中完成，已执行的版本记录在 `schema_migrations` 表中。不要修改已发布的迁移文件。
