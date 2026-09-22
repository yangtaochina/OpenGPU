// 展示层格式化工具

const DATE_TIME_FORMATTER = new Intl.DateTimeFormat('zh-CN', {
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hour12: false,
})

const FALLBACK = '-'

/** ISO 时间字符串 -> 本地时间文本；无效或空值返回 '-' */
export function formatDateTime(value: string | null | undefined): string {
  if (!value) return FALLBACK
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return DATE_TIME_FORMATTER.format(date).replace(/\//g, '-')
}

/** 相对时间，例如 “12 秒前”；无值返回 '-' */
export function formatRelativeTime(value: string | null | undefined): string {
  if (!value) return FALLBACK
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  const diffMs = Date.now() - date.getTime()
  const seconds = Math.round(diffMs / 1000)
  if (seconds < 0) return '刚刚'
  if (seconds < 60) return `${seconds} 秒前`
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) return `${minutes} 分钟前`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours} 小时前`
  const days = Math.floor(hours / 24)
  return `${days} 天前`
}

/** 字节数 -> 人类可读文本 */
export function formatFileSize(bytes: number | null | undefined): string {
  if (bytes == null || Number.isNaN(bytes)) return FALLBACK
  if (bytes < 1024) return `${bytes} B`
  const units = ['KB', 'MB', 'GB', 'TB']
  let size = bytes / 1024
  let unitIndex = 0
  while (size >= 1024 && unitIndex < units.length - 1) {
    size /= 1024
    unitIndex += 1
  }
  return `${size.toFixed(size >= 10 ? 0 : 1)} ${units[unitIndex]}`
}

/** 秒 -> “3.2 秒” / “1 分 05 秒” */
export function formatDuration(seconds: number | null | undefined): string {
  if (seconds == null || Number.isNaN(seconds)) return FALLBACK
  if (seconds < 60) return `${seconds.toFixed(1)} 秒`
  const minutes = Math.floor(seconds / 60)
  const rest = Math.round(seconds % 60)
  return `${minutes} 分 ${String(rest).padStart(2, '0')} 秒`
}

/** 分辨率文本 */
export function formatResolution(
  width: number | null | undefined,
  height: number | null | undefined,
): string {
  if (!width || !height) return FALLBACK
  return `${width} × ${height}`
}

/** 任务号短展示 */
export function shortId(id: string | null | undefined, length = 8): string {
  if (!id) return FALLBACK
  return id.length > length ? id.slice(0, length) : id
}

/** 提示词摘要 */
export function summarizePrompt(prompt: string | null | undefined, maxLength = 40): string {
  if (!prompt) return FALLBACK
  const singleLine = prompt.replace(/\s+/g, ' ').trim()
  return singleLine.length > maxLength ? `${singleLine.slice(0, maxLength)}…` : singleLine
}

/**
 * 结果文件地址 -> 可访问 URL。
 * fileUrl 为相对路径（如 /api/files/...）：
 * - VITE_API_BASE 为绝对地址时拼接为绝对 URL；
 * - 否则保持相对路径，开发环境由 vite proxy 处理。
 */
export function resolveFileUrl(fileUrl: string | null | undefined): string {
  if (!fileUrl) return ''
  if (/^https?:\/\//i.test(fileUrl)) return fileUrl
  const base = import.meta.env.VITE_API_BASE
  if (base && /^https?:\/\//i.test(base)) {
    return `${base.replace(/\/+$/, '')}${fileUrl.startsWith('/') ? '' : '/'}${fileUrl}`
  }
  return fileUrl
}
