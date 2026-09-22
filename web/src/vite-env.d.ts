/// <reference types="vite/client" />

interface ImportMetaEnv {
  /**
   * 控制面 API 基地址。
   * - 未定义（未配置）时使用默认值 http://localhost:8080
   * - 为空字符串时使用同源相对路径 /api/...（开发环境由 vite proxy 转发）
   */
  readonly VITE_API_BASE?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
