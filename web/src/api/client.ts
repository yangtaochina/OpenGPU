import axios, {
  type AxiosError,
  type AxiosRequestConfig,
  type AxiosResponse,
  type InternalAxiosRequestConfig,
} from 'axios'
import { ElMessage } from 'element-plus'

import type { ApiEnvelope } from '@/types/api'
import { clearAuthStorage, getToken } from '@/utils/token-storage'

/** 业务错误码：未认证 / 凭证无效 */
const CODE_UNAUTHORIZED = 40100

/**
 * 控制面基地址：
 * - 未配置 VITE_API_BASE 时回退到 http://localhost:8080
 * - 显式配置为空字符串时使用同源相对路径（/api/...，开发环境走 vite proxy）
 */
const envBase = import.meta.env.VITE_API_BASE
export const apiBaseURL = envBase === undefined ? 'http://localhost:8080' : envBase

export const client = axios.create({
  baseURL: apiBaseURL,
  timeout: 30_000,
})

function handleUnauthorized(): void {
  clearAuthStorage()
  const current = `${window.location.pathname}${window.location.search}`
  if (!current.startsWith('/login')) {
    const redirect = encodeURIComponent(current)
    window.location.href = `/login?redirect=${redirect}`
  }
}

function isEnvelope(value: unknown): value is ApiEnvelope<unknown> {
  return (
    typeof value === 'object' &&
    value !== null &&
    'code' in value &&
    typeof (value as { code?: unknown }).code === 'number'
  )
}

// 请求拦截：附加 JWT
client.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const token = getToken()
  if (token) {
    config.headers.set('Authorization', `Bearer ${token}`)
  }
  return config
})

// 响应拦截：拆信封 / 处理 401 / 透传 blob 与 204
client.interceptors.response.use(
  (response: AxiosResponse) => {
    // 204 No Content 或文件下载（blob/arraybuffer）直接透传
    if (response.status === 204) {
      return response.data
    }
    const payload = response.data
    if (payload instanceof Blob || payload instanceof ArrayBuffer) {
      return payload
    }
    if (isEnvelope(payload)) {
      if (payload.code === 0) {
        return payload.data
      }
      if (payload.code === CODE_UNAUTHORIZED) {
        handleUnauthorized()
      }
      const message = payload.message || '请求失败'
      ElMessage.error(message)
      return Promise.reject(new Error(message))
    }
    // 非信封响应（理论上不该出现）原样返回
    return payload
  },
  (error: AxiosError) => {
    const status = error.response?.status
    const payload = error.response?.data
    const envelope = isEnvelope(payload) ? payload : undefined

    if (status === 401 || envelope?.code === CODE_UNAUTHORIZED) {
      handleUnauthorized()
      const message = envelope?.message || '登录已失效，请重新登录'
      ElMessage.error(message)
      return Promise.reject(error)
    }

    let message = '网络异常，请稍后重试'
    if (envelope?.message) {
      message = envelope.message
    } else if (error.code === 'ECONNABORTED') {
      message = '请求超时，请稍后重试'
    } else if (error.message) {
      message = error.message
    }
    ElMessage.error(message)
    return Promise.reject(error)
  },
)

/**
 * 类型化调用封装：响应拦截器已把信封拆成 `data`，
 * 因此这里把返回值断言为业务数据类型。
 */
export const http = {
  get<T>(url: string, config?: AxiosRequestConfig): Promise<T> {
    return client.get(url, config) as unknown as Promise<T>
  },
  post<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
    return client.post(url, data, config) as unknown as Promise<T>
  },
  put<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
    return client.put(url, data, config) as unknown as Promise<T>
  },
  delete<T>(url: string, config?: AxiosRequestConfig): Promise<T> {
    return client.delete(url, config) as unknown as Promise<T>
  },
}

export default client
