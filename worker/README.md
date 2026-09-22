# OpenGPU GPU Worker

常驻 Python 进程，负责向 **智由 AI 视频任务平台** 注册节点、领取任务、执行生成、
上传结果并回传 `complete` / `fail`。

- 唯一第三方依赖：**`requests`**（不使用 python-dotenv / pydantic / httpx）。
- 接口契约以 [`docs/API.md`](../docs/API.md) 为准（信封 `{code,message,data}`、
  `Authorization: Bearer wkr_<uuid>_<secret>`、`40910` = 租约失效）。
- Python 3.13（`3.11+` 亦可运行）。

---

## 1. 前置条件

| 项 | 说明 |
|---|---|
| Python | 3.13（推荐），最低 3.11 |
| 依赖 | `requests` |
| 网络 | 能访问控制面 `OPENGPU_API_BASE`（默认 `http://localhost:8080`） |
| 可选 | `ffmpeg` + `ffprobe`（mock 引擎生成真实可播放 mp4） |
| GPU | 仅 H3 引擎需要（M0 部署验证通过后） |

## 2. 安装

```bat
cd worker
python -m venv .venv
.venv\Scripts\python.exe -m pip install -r requirements.txt
```

POSIX：

```sh
cd worker
python3 -m venv .venv
.venv/bin/python -m pip install -r requirements.txt
```

## 3. 获取 Worker Token

Token 仅在创建节点时返回一次，格式 `wkr_<workerId>_<secret>`。

**方式一：管理端页面**
1. 登录平台管理端（默认账号 `admin` / `admin123`）。
2. 进入「节点 / Workers」，新建节点，填写名称、GPU 型号、显存等。
3. 立即复制一次性下发的 token。

**方式二：直接调用 API**

```bash
# 1) 登录拿 JWT
curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'

# 2) 创建节点（用上一步返回的 token 替换 <JWT>）
curl -s -X POST http://localhost:8080/api/admin/workers \
  -H "Authorization: Bearer <JWT>" \
  -H "Content-Type: application/json" \
  -d '{"name":"gpu-node-01","gpuModel":"RTX 4090","vramMb":24576,"workerVersion":"0.1.0","modelVersion":"MiniMax-H3"}'
```

响应中的 `data.token` 即 worker token，请写入 `worker/.env` 或环境变量。

## 4. 配置

复制模板并填写：

```bat
copy .env.example .env
```

`.env` 由 worker 自己解析（简单 `KEY=VALUE`，支持 `#` 注释与引号），
真实环境变量优先级高于 `.env`。

## 5. Mock 模式运行（默认，无需 GPU）

```bat
run.bat
```

POSIX：

```sh
chmod +x run.sh
./run.sh
```

行为：

1. 启动时 `POST /api/workers/register` 上报基础信息。
2. 后台守护线程每 `OPENGPU_HEARTBEAT_INTERVAL` 秒发送心跳（空闲 `IDLE`，
   执行中 `BUSY` + `currentTaskId`，服务端据此续租）。
3. 空闲时 `POST /api/worker/tasks/claim` 长轮询；`204` 表示无任务，继续轮询。
4. 领取到任务后调用 mock 引擎，进度 `0 → 90` 按 `OPENGPU_MOCK_DURATION_SECONDS`
   每约 2 秒上报一次（同时续租），随后生成文件并上报 `100`。
5. 上传结果：`/api/worker/files/presign` → `multipart /api/worker/files`。
6. `POST /api/worker/tasks/{taskId}/complete`（失败则 `/fail`）。

**mock 产物优先级**（保证「可播放」）：

1. `ffmpeg` 在 PATH 上 → 生成约 3 秒的 `testsrc` + 440Hz 音频（H.264/AAC，
   `yuv420p`），并用 `ffprobe` 读取真实时长/宽高。
2. 否则 `OPENGPU_MOCK_SAMPLE_VIDEO` 指向的文件存在 → 复制该文件并探测。
3. 都没有 → 写一个占位文件并打印 **WARNING**：该文件不可播放，
   该任务 **不得视为 AT-03 已验证**。

## 6. 切换到 H3 模式

> ⚠️ **M0 前置条件**：`h3` 引擎目前是 **M0 STUB**，只有在
> **MiniMax H3 单机部署验证通过之后** 才能启用。启用前 `h3_engine.py` 中的
> 分辨率 / 时长等数值均为 **未经验证的占位值**（文件内有显式注释）。

```bat
set OPENGPU_ENGINE=h3
set OPENGPU_H3_COMMAND=python infer.py --prompt {prompt} --out {output}
run.bat
```

或写入 `.env`：

```
OPENGPU_ENGINE=h3
OPENGPU_H3_COMMAND=python infer.py --prompt {prompt} --out {output}
OPENGPU_H3_TIMEOUT=3600
```

命令模板说明：

- 支持 `{prompt}`（任务提示词）与 `{output}`（必须写入的绝对输出路径）两个占位符。
- 模板被解析为 **argv 列表** 后用 `subprocess.run(argv, shell=False)` 执行，
  **不经过 shell**，因此提示词中的空格/元字符不会造成命令注入。
- 因此模板只能是「普通命令」，不能包含 `&&`、管道、重定向等；复杂启动逻辑请写
  一个包装脚本。
- 失败分类：`CUDA_OOM`（显存不足）、`DISK_FULL`、`MODEL_LOAD_FAILED`、
  `TIMEOUT`、`INFERENCE_FAILED`、`CONFIG_ERROR`。

## 7. 停止

- `Ctrl+C`（SIGINT）或 SIGTERM 触发优雅退出：停止心跳线程，**不会**把执行中的
  任务标记为 `fail`，而是让服务端租约自然过期回收。
- 重启后不会破坏已完成任务：`complete` 幂等（AT-06），且本进程不持有持久状态。

## 8. 环境变量

| 变量 | 默认值 | 必填 | 说明 |
|---|---|---|---|
| `OPENGPU_API_BASE` | `http://localhost:8080` | 否 | 控制面基地址 |
| `OPENGPU_WORKER_TOKEN` | — | **是** | `wkr_<uuid>_<secret>` |
| `OPENGPU_WORKER_NAME` | 主机名 | 否 | 节点名称 |
| `OPENGPU_GPU_MODEL` | `unknown` | 否 | GPU 型号，上报给平台 |
| `OPENGPU_VRAM_MB` | `0` | 否 | 显存（MB），上报给平台 |
| `OPENGPU_WORKER_VERSION` | `0.1.0` | 否 | Worker 版本 |
| `OPENGPU_MODEL_VERSION` | `MiniMax-H3` | 否 | 模型版本 |
| `OPENGPU_HEARTBEAT_INTERVAL` | `20` | 否 | 心跳间隔秒数（最小 5；服务端可覆盖） |
| `OPENGPU_POLL_WAIT_SECONDS` | `20` | 否 | claim 长轮询秒数，0–30（超过 30 截断） |
| `OPENGPU_WORK_DIR` | `./work` | 否 | 工作目录，相对路径基于 `worker/` |
| `OPENGPU_MAX_UPLOAD_BYTES` | `2147483648` | 否 | 结果文件上限（2 GiB） |
| `OPENGPU_ENGINE` | `mock` | 否 | `mock` \| `h3` |
| `OPENGPU_MOCK_DURATION_SECONDS` | `6` | 否 | mock 模拟渲染耗时（进度 0→100） |
| `OPENGPU_MOCK_SAMPLE_VIDEO` | — | 否 | ffmpeg 缺失时复制的示例视频路径 |
| `OPENGPU_H3_COMMAND` | — | 否 | H3 命令模板（`h3` 模式必填） |
| `OPENGPU_H3_TIMEOUT` | `3600` | 否 | H3 单次推理超时秒数 |
| `OPENGPU_LOG_LEVEL` | `INFO` | 否 | `DEBUG` \| `INFO` \| `WARNING` \| `ERROR` |

## 9. 目录结构

```
worker/
  requirements.txt
  README.md
  .env.example
  run.bat            # Windows 启动脚本
  run.sh             # POSIX 启动脚本
  worker.py          # 主循环 / 入口
  config.py          # 环境变量配置 + 极简 .env 解析
  api_client.py      # HTTP 客户端、信封解包、异常、重试
  engines/
    __init__.py      # 引擎注册表 / 工厂
    base.py          # Engine ABC + TaskResult + 公共工具
    mock_engine.py   # 本地测试引擎
    h3_engine.py     # MiniMax H3 适配器（M0 stub）
```

## 10. 错误码与重试策略

引擎失败经 `/fail` 上报：

| errorCode | retryable | 说明 |
|---|---|---|
| `CUDA_OOM` | ✅ | 显存不足 |
| `DISK_FULL` | ✅ | 磁盘空间不足 |
| `TIMEOUT` | ✅ | 推理超时 |
| `INFERENCE_FAILED` | ✅ | 其它推理失败 |
| `UPLOAD_FAILED` | ✅ | 上传失败 |
| `MODEL_LOAD_FAILED` | ❌ | 模型/命令加载失败（配置问题） |
| `CONFIG_ERROR` | ❌ | `OPENGPU_H3_COMMAND` 未配置或无法解析 |
| `FILE_TOO_LARGE` | ❌ | 结果超过大小限制 |

HTTP 层：连接错误与 5xx 指数退避 + 抖动重试（心跳/claim）；
`complete` 因服务端幂等可重试 2–3 次；4xx 不重试；
`HTTP 409 + code 40910` 映射为 `LeaseExpiredError`，主循环静默放弃该任务，
**绝不** 上报 `fail`。
