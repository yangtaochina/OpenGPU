# 智由 AI 视频任务平台（OpenGPU）

> 固定 MiniMax H3 模型 · 远程 GPU Worker · 任务队列
> 主体：宜宾智由科技有限公司 ｜ 阶段：原型验证（MVP V1）
> 需求来源：`files/智由AI视频任务平台_MVP需求文档_V1.0.docx`

用户在网页提交视频生成任务，平台写入队列；安装在远程出租 GPU 电脑上的 Worker 主动领取任务，
调用本机固定的 MiniMax H3 完成推理，再把结果回传平台供用户查看或下载。

**第一版的核心验证目标**：远程 GPU 节点能否稳定完成「领取任务 → 执行推理 → 上传结果 → 更新状态」的完整闭环。
不训练模型，不把一次推理拆分到多台电脑。

---

## 1. 当前进度

| 阶段 | 内容 | 状态 |
|---|---|---|
| M0 模型验证 | 在目标 GPU 电脑独立跑通 MiniMax H3 | ⬜ 未开始（**开发前置门槛**，见 §7） |
| M1 任务闭环 | 任务 API + 队列 + Worker 拉取 + 结果回传 | 🟡 骨架已完成，用 Mock 引擎打通 |
| M2 网页可用 | 用户页面 + 基础管理页面 | 🟡 骨架已完成 |
| M3 异常验证 | 租约、超时、失败、人工重试 + 验收记录 | 🟡 代码已完成，自动化验收脚本就绪 |

已实现并自动化验证的能力（AT-01 ~ AT-14）：任务状态机与租约、原子领取、
幂等上报、结果文件校验、失败重试与租约回收、节点停用、非法输入拒绝、
**视频要求 → 显卡能力匹配**、**开放用户注册**、**管理端用户管理**。

### 账号体系

- **开放注册**：`POST /api/auth/register`，注册即自动登录。角色**固定为 `USER`**，
  请求体里即使带了 `role` 也会被忽略，避免通过注册接口提权。
- **注册开关与密码策略**通过 `GET /api/auth/config` 下发给前端，前端不硬编码规则；
  开关由 `opengpu.security.registration-enabled` 控制。
- **管理端用户管理**：列表（关键字/角色/状态筛选）、建号（可指定角色）、启停、
  改角色、重置密码。所有密码均以 BCrypt 存储，任何响应都不含 `passwordHash`。
- **防锁死保护**（服务端强制，返回 `40302`）：不能停用自己、不能修改自己的角色、
  不能停用或降级**最后一个处于启用状态的管理员**。

### 视频要求与显卡匹配

用户在提交任务时可以选择视频要求（分辨率 / 时长 / 帧率），平台据此推导出所需的
**显存下限**与**显卡档位**；Worker 在注册时上报自身显卡能力；领取任务时服务端按能力过滤，
**只有满足要求的节点才能领到该任务**。

- 推导规则与档位划分见 `docs/API.md` §7
- 当前可服务范围通过 `GET /api/capabilities` 返回，前端据此禁用不可用组合并提示「等待节点」
- 无任何在线节点满足要求时，任务保持 `QUEUED` 而不是报错或静默卡死
- 这是**基于能力的规则匹配**，不是性能预测或机器学习调度；同显存的不同显卡视为同级

> ⚠️ 推导公式中的系数是**工程占位值**，必须等 M0（MiniMax H3 单机验证）完成后用实测峰值显存校准，
> 校准只需改 `VideoRequirements` 一个类。在此之前不得作为对外承诺。

节点未上报显存（`OPENGPU_VRAM_MB=0`）时视为「能力未知」，只能领取不限定硬件的任务。

---

## 2. 目录结构

```
OpenGPU/
├─ start.bat             # ★ 双击一键启动（依赖 + 后端 + 前端 + Worker）
├─ stop.bat              # 双击一键停止
├─ docs/
│  ├─ API.md             # ★ 前后端与 Worker 共同遵守的接口契约
│  └─ ACCEPTANCE.md      # AT-01 ~ AT-10 验收对照与执行说明
├─ deploy/
│  └─ docker-compose.yml # PostgreSQL / Redis / MinIO
├─ platform-api/         # 控制面 API（Spring Boot）
├─ web/                  # 前端（Vue 3 + Vite + Element Plus）
├─ worker/               # GPU Worker（Python 常驻进程）
├─ scripts/
│  ├─ start-all.ps1      # 一键启动（start.bat 的实际实现）
│  ├─ start-detached.cmd # 脱离终端启动（远程 shell / CI 场景）
│  ├─ stop-all.ps1       # 一键停止
│  ├─ acceptance.ps1     # 端到端验收测试脚本（AT-01 ~ AT-12）
│  ├─ loadtest.py        # 并发压测：任务创建 + 原子领取
│  └─ loadtest-longpoll.py # 并发压测：长轮询线程占用
└─ files/                # 原始需求文档
```

### 控制面与执行面分离

| 层 | 职责 | 技术 |
|---|---|---|
| 控制面 | 用户、任务、节点状态、文件元数据 | Spring Boot + PostgreSQL + Redis + MinIO |
| 执行面 | 领取任务、调用模型、上传结果 | Python 常驻进程（仅在远程 GPU 电脑上） |

**关键设计**：PostgreSQL 是任务事实来源，原子领取使用 `SELECT ... FOR UPDATE SKIP LOCKED`；
Redis Streams 只用于把「有新任务」事件尽快推给正在长轮询的 Worker，Redis 不可用时自动退化为轮询，功能不受影响。

Worker 全部主动出网，规避出租电脑处于 NAT / 防火墙后的问题。

---

## 3. 快速开始

> **最快方式：双击项目根目录下的 `start.bat`。**
> 它会依次启动 Docker 依赖组件、后端、前端和 Worker（Mock 引擎，**无需 GPU**），并自动打开浏览器。
> 停止：双击 `stop.bat`（加 `-WithContainers` 可一并关掉 Docker 依赖，见下）。
>
> 也可以直接用 PowerShell：
>
> ```powershell
> # 启动全部
> powershell -ExecutionPolicy Bypass -File scripts\start-all.ps1
>
> # 停止全部（仅停应用进程，保留 Docker 容器与数据）
> powershell -ExecutionPolicy Bypass -File scripts\stop-all.ps1
>
> # 停止并一并关掉 Docker 依赖（加 -CleanData 则连数据卷一起删除）
> powershell -ExecutionPolicy Bypass -File scripts\stop-all.ps1 -WithContainers
> ```
>
> `start.bat` / `stop.bat` 会把命令行参数原样透传，例如 `start.bat -SkipWorker`、`stop.bat -WithContainers`。
>
> | 参数 | 说明 |
> |---|---|
> | `-SkipFrontend` | 不启动前端 |
> | `-SkipWorker` | 不启动 Worker |
> | `-SkipBuild` | 不打包后端（默认仅在 jar 不存在时打包） |
> | `-Rebuild` | 强制重新打包后端 |
>
> 各组件后台运行，日志统一写到 `logs\`（`api.log` / `web.log` / `worker.log`）。
> 启动脚本会自动拉起 Docker Desktop、等待 PostgreSQL 变为 healthy、并在启动前按需打包后端；
> 重复双击是安全的：已在运行的组件会被识别并跳过。
>
> 两个补充说明：
> - 在 Windows 任务管理器里看到**两个 `python.exe`** 属于正常现象：venv 的 `Scripts\python.exe` 是转发器，
>   会把基础解释器作为子进程拉起，两者是同一个 Worker。
> - 需要**脱离当前终端常驻**（远程 shell / CI）时，用 `scripts\start-detached.cmd`；
>   普通本地开发不需要它。

下面 3.0 ~ 3.4 是**分步手动启动**方式，便于定位问题。

### 3.0 环境要求

| 组件 | 版本 |
|---|---|
| JDK | 17+ |
| Maven | 3.8+ |
| Node.js | 20+ |
| Python | 3.10+ |
| Docker | 任意近期版本（仅用于本地依赖） |

### 3.1 启动依赖组件

```powershell
docker compose -f deploy/docker-compose.yml up -d postgres redis
```

- PostgreSQL：`localhost:5432`，库 `opengpu`，账号 `opengpu / opengpu`
- Redis：`localhost:6379`

后端默认使用**本地磁盘**存储结果文件（`platform-api/data/videos`），因此 MinIO 不是必需组件。
需要对象存储时改用 `opengpu.storage.type=minio` 并启动：

```powershell
docker compose -f deploy/docker-compose.yml up -d minio minio-init
```

> 若本机无法直连 Docker Hub，可先从国内镜像源拉取再打回标准 tag：
> ```powershell
> docker pull docker.m.daocloud.io/library/postgres:16-alpine
> docker tag  docker.m.daocloud.io/library/postgres:16-alpine postgres:16-alpine
> docker pull docker.m.daocloud.io/library/redis:7-alpine
> docker tag  docker.m.daocloud.io/library/redis:7-alpine redis:7-alpine
> ```

### 3.2 启动控制面 API

```powershell
cd platform-api
mvn -DskipTests package
java -jar target/opengpu-platform-api.jar
```

启动时 Flyway 自动建表，并打印内置账号与开发节点凭证：

```
已创建内置账号: admin / admin123  (ADMIN)
已创建内置账号: user / user123    (USER)
已创建开发用 GPU 节点（仅用于本地联调）
节点名称 : dev-worker-01
节点 ID  : 00000000-0000-0000-0000-000000000001
Token    : wkr_00000000-0000-0000-0000-000000000001_devsecret
```

- 接口文档：http://localhost:8080/swagger-ui.html
- 健康检查：http://localhost:8080/actuator/health

### 3.3 启动前端

```powershell
cd web
npm install
npm run dev
```

打开 http://localhost:5173 ，使用 `admin / admin123` 登录。
开发模式下 Vite 会把 `/api` 代理到 `http://localhost:8080`。

### 3.4 启动 GPU Worker

```powershell
cd worker
pip install -r requirements.txt
$env:OPENGPU_WORKER_TOKEN="wkr_00000000-0000-0000-0000-000000000001_devsecret"
python worker.py
```

默认 `OPENGPU_ENGINE=mock`，会走完整闭环并生成一段测试视频。
如果本机没有 `ffmpeg`，Mock 引擎只能产出占位文件（会打印明确 WARNING），
此时 **不要把它当成 AT-03 已验证**。

切换到真实模型（需先完成 M0）：

```powershell
$env:OPENGPU_ENGINE="h3"
$env:OPENGPU_H3_COMMAND="python C:\h3\run.py --prompt {prompt} --out {output}"
python worker.py
```

完整环境变量表见 `worker/README.md`。

---

## 4. 接口契约

**唯一权威来源：`docs/API.md`**。任何一方改接口必须同步该文件。

- 统一响应信封：`{ "code": 0, "message": "success", "data": {...} }`
- 认证：用户/管理员走 JWT；Worker 走独立静态 token（`wkr_<workerId>_<secret>`，服务端只存 SHA-256）
- 任务状态机：`QUEUED → ASSIGNED → RUNNING → UPLOADING → SUCCEEDED`，另有 `FAILED` / `CANCELED`

---

## 5. 关键配置

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `opengpu.security.jwt-secret` | 开发默认值 | **生产必须用环境变量 `OPENGPU_JWT_SECRET` 覆盖** |
| `opengpu.security.registration-enabled` | `true` | 是否开放自助注册；关闭后注册接口返回 `40301` |
| `opengpu.security.username-min-length` / `-max-length` | `3` / `32` | 用户名长度；仅允许字母数字下划线连字符 |
| `opengpu.security.password-min-length` / `-max-length` | `6` / `64` | 密码长度（原型阶段仅校验长度，无复杂度要求） |
| `opengpu.task.lease-seconds` | 300 | 租约时长，进度/心跳会续租 |
| `opengpu.task.max-retry` | 2 | 自动重试上限，超过则 `FAILED` |
| `opengpu.task.heartbeat-timeout-seconds` | 90 | 超过则节点标记 `OFFLINE` |
| `opengpu.task.lease-reaper-interval-seconds` | 15 | 租约回收扫描周期 |
| `opengpu.task.max-file-size-bytes` | 2147483648 | 单个结果文件上限（2 GiB） |
| `opengpu.queue.redis-enabled` | `true` | 是否用 Redis Streams 做低延迟唤醒；置 `false` 则退化为数据库轮询（功能一致，仅多约 1 秒延迟） |
| `opengpu.storage.type` | `local` | `local` 或 `minio` |
| `opengpu.cors.allowed-origins` | `http://localhost:5173` | 前端来源白名单 |

**Worker 侧能力声明**（`worker/.env`，决定能领到哪些任务）：

| 变量 | 默认 | 说明 |
|---|---|---|
| `OPENGPU_VRAM_MB` | `0` | 显存（MB）。**`0` 表示未上报，只能领取不限硬件的任务**；真实节点务必填写 |
| `OPENGPU_GPU_TIER` | 空 | `ENTRY` / `STANDARD` / `PRO` / `ULTRA`；留空由服务端按显存推导 |
| `OPENGPU_MAX_DURATION_SECONDS` | `0` | 可承受的最长时长；`0` 表示不限制 |
| `OPENGPU_SUPPORTED_RESOLUTIONS` | 空 | 仅用于管理端展示 |

---

## 6. 验收测试

```powershell
# 前置：Docker 依赖已启动，platform-api 已运行
# 注意：先停掉 Worker，否则它会抢走脚本要领取的任务
powershell -ExecutionPolicy Bypass -File scripts\stop-all.ps1
powershell -ExecutionPolicy Bypass -File scripts\start-all.ps1 -SkipWorker -SkipFrontend
powershell -ExecutionPolicy Bypass -File scripts\acceptance.ps1

# 额外跑租约到期回收（AT-05，建议先把 lease-seconds 调小）
powershell -ExecutionPolicy Bypass -File scripts\acceptance.ps1 -IncludeSlowTests
```

覆盖 AT-01 / AT-02 / AT-04 / AT-06 / AT-07 / AT-08 / AT-09 / AT-10。
AT-03（真实生成可播放视频）与 AT-05（租约回收）依赖环境，说明见 `docs/ACCEPTANCE.md`。

---

## 7. ⚠️ M0 是开发前置门槛

MiniMax H3 是本项目选定的固定模型，但**在把 Worker 接入真实推理之前，必须先完成单机部署验证**：

- 模型身份与许可（准确仓库、权重版本、商用限制）
- 硬件适配（GPU 型号、显存、内存、磁盘、驱动、CUDA）
- 运行方式（官方脚本、推理框架、启动参数、依赖锁定）
- 输入输出（提示词字段、可选参数、输出容器与编码）
- 耗时与资源（加载时间、生成时间、峰值显存、磁盘占用）
- 错误恢复（显存不足、中断、磁盘不足、上传失败）

**未经验证的数据不得作为产品承诺。** 当前 `worker/engines/h3_engine.py` 中的分辨率、
时长等取值均为**占位值**，M0 通过后必须用真实测试数据替换。

---

## 8. 并发与容量（实测）

测法：`scripts/loadtest.py`（任务创建 / 原子领取）、`scripts/loadtest-longpoll.py`（长轮询线程占用）。

**测量环境与局限性**：单机，压测客户端、后端、PostgreSQL(Docker) 共用同一台机器。
因此下面的数字**偏保守**，不能代表分离部署的真实上限；但也正因为同机，
它反映的是「开发机能跑到什么程度」。

### 8.1 实测数据

| 并发 | 创建 p50 | 创建吞吐 | 领取 p50 | 领取吞吐 | 任务被重复分配 |
|---|---|---|---|---|---|
| 1 | 19 ms | 40 req/s | 29 ms | 30 req/s | **0** |
| 10 | 49 ms | **201 req/s** | 63 ms | 126 req/s | **0** |
| 30 | 195 ms | 106 req/s | 284 ms | 77 req/s | **0** |
| 100 | 580 ms | 167 req/s | 634 ms | 118 req/s | **0** |

- 每轮 150~400 个任务，**零失败、零重复分配**。`SELECT ... FOR UPDATE SKIP LOCKED` + 租约的
  正确性在并发下成立——这是任务队列最关键的不变量。
- 吞吐在并发 10 附近最好，再往上延迟上升而吞吐不增，说明瓶颈在单机资源而非应用逻辑。
- 把 Hikari 连接池从 20 调到 50 后吞吐**反而下降**（167 → 119 req/s），据此排除「连接池是瓶颈」的猜测。
  （因此保持默认 20，未做修改。）

### 8.2 已知的硬上限（按重要性排序）

**1. 长轮询会占满 Tomcat 线程 —— 这是最硬的上限**

每个等待中的 Worker 都会占住一个请求线程，Tomcat 默认 `max-threads=200`。
实测：250 个长轮询同时挂起时，一个普通探针请求**最坏等待 11.2 秒**（基线 47 ms，放大 238 倍）。

> 也就是说：**并发等待任务的 Worker 超过约 200 个时，平台对用户请求基本失去响应。**
> 缓解方向：claim 改为异步非阻塞 / 缩短 `waitSeconds` 让客户端退避 / 改为服务端推送。

**2. Redis 唤醒是广播式的（惊群）**

所有 Worker 阻塞在同一个 Stream 且从 `$` 读取，一个新任务会唤醒**全部**等待者，
最终只有一个抢到，其余重新阻塞并再查一次数据库。W 个 Worker、T 个任务 → 最坏 W×T 次查询。
缓解方向：改用 Redis Streams **消费组**，或 `LPUSH` + `BRPOP` 让每个任务只唤醒一个等待者。

**3. 结果文件走平台中转上传**

一个 2 GB 视频会长时间占用一个 Tomcat 线程与内存缓冲。
缓解方向：Worker 用预签名地址直传 MinIO（契约中已预留 `mode=presigned-put`）。

**4. 前端 3 秒轮询**

1000 个在线用户 ≈ 333 req/s 的列表查询。缓解方向：SSE/WebSocket 推送，或 ETag 条件请求。

**5. Worker 凭证每次请求都查库**

每个 Worker 请求都会 `select` 一次 worker 再比对 token 哈希。缓解方向：加一层凭证缓存。

### 8.3 结论

- 以需求文档的 V1 目标衡量（平台接口目标 < 2 秒、内测规模），**当前实现够用**：
  百级并发下零错误、零任务错配。
- 但**它不是一个高并发系统**：没有做过分离部署压测、没有接口限流、长轮询有明确的线程悬崖。
- 要往上走，**第 1、2 条必须先解决**；这两条都是局部改动，不需要重构架构。

---

## 9. V1 明确不做

多模型 · 自动部署 · 计费结算 · 开放出租市场 · 企业私有节点 ·
复杂调度（竞价/地域/评分）· 分布式训练 · 高可用 SLA。

---

## 10. 已知限制（内测可接受，对外开放前必须处理）

1. **结果文件免认证下载**：`GET /api/files/**` 无鉴权（`<video>` 无法携带 Authorization 头）。
   文件名为随机 UUID，V1 内测接受；对外开放前需改为签名 URL 或短期令牌。
2. **无租户隔离**：所有登录用户可见全部任务（需求文档 2.2 明确排除租户隔离）。
3. **开发节点为固定凭证**：`dev-worker-01` 的 token 写死在配置里，仅用于本地联调。
4. **平台中转上传**：结果文件先上传到平台再落库，Worker 直传（预签名 PUT）为后续优化项。
5. **平台无鉴权限流**：未做接口限流与防刷。
6. **密码策略偏弱**：只校验长度（6–64 位），没有大小写/数字/符号复杂度要求，也没有常见弱口令黑名单。对外开放前必须加强，并补上注册频率限制与验证码。
7. **停用账号不吊销已签发的 JWT**：停用只阻止再次登录，已签发 token 在过期前（默认 12 小时）仍然有效。V1 不维护 token 黑名单。
8. **注册无邮箱验证**：`AppUser` 只有用户名，没有邮箱/手机号，因此无法做找回密码与身份验证。这是有意的范围取舍，需要时再扩展。
