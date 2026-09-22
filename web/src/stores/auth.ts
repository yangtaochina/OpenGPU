import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

import { fetchMe, login as loginApi, register as registerApi } from '@/api/auth'
import type { LoginRequest, LoginResponse, RegisterRequest, Role } from '@/types/api'
import {
  clearAuthStorage,
  getStoredRole,
  getStoredUsername,
  getToken,
  setStoredAuth,
} from '@/utils/token-storage'

export const useAuthStore = defineStore('auth', () => {
  const token = ref<string>(getToken())
  const username = ref<string>(getStoredUsername())
  const role = ref<Role | ''>((getStoredRole() as Role) || '')

  const isAuthenticated = computed(() => token.value.length > 0)
  const isAdmin = computed(() => role.value === 'ADMIN')

  function applyAuth(payload: { token: string; username: string; role: Role }): void {
    token.value = payload.token
    username.value = payload.username
    role.value = payload.role
    setStoredAuth(payload.token, payload.username, payload.role)
  }

  async function login(payload: LoginRequest): Promise<LoginResponse> {
    const result = await loginApi(payload)
    applyAuth(result)
    return result
  }

  /** 开放注册：注册成功即自动登录（与 login 行为一致） */
  async function register(payload: RegisterRequest): Promise<LoginResponse> {
    const result = await registerApi(payload)
    applyAuth(result)
    return result
  }

  /** 用服务端 /api/auth/me 校准本地角色信息（可选） */
  async function refreshMe(): Promise<void> {
    const me = await fetchMe()
    if (token.value) {
      applyAuth({ token: token.value, username: me.username, role: me.role })
    }
  }

  function logout(): void {
    token.value = ''
    username.value = ''
    role.value = ''
    clearAuthStorage()
  }

  return {
    token,
    username,
    role,
    isAuthenticated,
    isAdmin,
    login,
    register,
    logout,
    refreshMe,
  }
})
