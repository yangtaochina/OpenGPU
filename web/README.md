# 智由 AI 视频任务平台 — Web 前端

Vue 3 + Vite + TypeScript + vue-router 4 + Pinia + Element Plus + axios 实现的控制台前端。

接口契约以 `../docs/API.md` 为唯一准绳。

## 环境要求

- Node.js 22（`node -v`）
- npm 10（`npm -v`）

## 快速开始

```bash
npm install
npm run dev      # http://localhost:5173
```

开发环境下 `VITE_API_BASE` 留空，前端以同源 `/api/...` 访问，由 `vite.config.ts` 中的
proxy 转发到控制面 `http://localhost:8080`。因此 `<video src="/api/files/...">` 可直接播放。

若控制面不在本机 8080，可修改 `vite.config.ts` 的 proxy target，或设置
`VITE_API_BASE=http://host:port`（此时请求直连该地址，需要控制面允许 CORS）。

## 脚本

| 命令 | 说明 |
|---|---|
| `npm run dev` | 启动开发服务器（端口 5173） |
| `npm run build` | `vue-tsc --noEmit && vite build`，类型检查 + 生产构建 |
| `npm run preview` | 预览 `dist/` 构建产物 |
| `npm run type-check` | 仅执行 TypeScript 类型检查 |

## 环境变量

| 变量 | 说明 |
|---|---|
| `VITE_API_BASE` | 控制面基地址。未配置时默认 `http://localhost:8080`；配置为空字符串时使用同源 `/api/...`。 |

见 `.env.development` / `.env.production`。

## 路由

| 路由 | 页面 | 权限 |
|---|---|---|
| `/login` | 登录 | 公开 |
| `/` | 提交任务 + 我的任务列表 | 登录 |
| `/tasks/:id` | 任务详情 | 登录 |
| `/admin/tasks` | 管理端任务列表（筛选 / 分页 / 重试） | ADMIN |
| `/admin/workers` | 管理端节点列表（启停 / 新建并一次性展示 token） | ADMIN |
| `*` | 404 | 公开 |

开发种子账号：`admin/admin123`（ADMIN）、`user/user123`（USER）。

## 目录结构

```
src/
  api/          # axios 实例与接口封装（auth / tasks / workers）
  components/   # StatusTag 等通用组件
  layouts/      # MainLayout 顶部导航外壳
  router/       # 路由与鉴权守卫
  stores/       # Pinia（auth）
  styles/       # 全局样式
  types/        # 契约类型定义
  utils/        # 状态映射、格式化、token 持久化
  views/        # 页面
```

## 关键实现

- **响应拆封**：`src/api/client.ts` 的响应拦截器在 `code === 0` 时返回 `data`，
  否则 `ElMessage.error(message)` 并 reject；`40100` 或 HTTP 401 时清除 token 并跳转 `/login`。
  文件下载（blob）与 `204 No Content` 直接透传。
- **轮询**：任务列表与任务详情在存在非终态任务（`QUEUED|ASSIGNED|RUNNING|UPLOADING`）时每 3 秒轮询，
  全部进入终态后停止，`onUnmounted` 必定清理定时器。节点列表每 5 秒刷新。
- **状态展示**：`src/utils/status.ts` 统一维护 `TaskStatus` / `WorkerStatus` /
  `WorkerRuntimeStatus` / `AttemptStatus` 的中文标签与 `el-tag` 类型。
- **结果视频地址**：`resolveFileUrl` 在 `VITE_API_BASE` 为绝对地址时拼接为绝对 URL，
  否则返回相对路径以适配开发代理。
