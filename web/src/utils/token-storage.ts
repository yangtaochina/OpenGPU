// 本地持久化：token / username / role。
// 独立于 Pinia store，避免 api/client 与 store 之间产生循环依赖。
import type { Role } from '@/types/api'

const TOKEN_KEY = 'opengpu.token'
const USERNAME_KEY = 'opengpu.username'
const ROLE_KEY = 'opengpu.role'

export function getToken(): string {
  return localStorage.getItem(TOKEN_KEY) ?? ''
}

export function getStoredUsername(): string {
  return localStorage.getItem(USERNAME_KEY) ?? ''
}

export function getStoredRole(): string {
  return localStorage.getItem(ROLE_KEY) ?? ''
}

export function setStoredAuth(token: string, username: string, role: Role): void {
  localStorage.setItem(TOKEN_KEY, token)
  localStorage.setItem(USERNAME_KEY, username)
  localStorage.setItem(ROLE_KEY, role)
}

export function clearAuthStorage(): void {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USERNAME_KEY)
  localStorage.removeItem(ROLE_KEY)
}
