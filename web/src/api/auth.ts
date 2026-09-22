import { http } from '@/api/client'
import type {
  AuthConfigView,
  LoginRequest,
  LoginResponse,
  MeView,
  RegisterRequest,
} from '@/types/api'

/** POST /api/auth/login */
export function login(payload: LoginRequest): Promise<LoginResponse> {
  return http.post<LoginResponse>('/api/auth/login', payload)
}

/** GET /api/auth/me */
export function fetchMe(): Promise<MeView> {
  return http.get<MeView>('/api/auth/me')
}

/** GET /api/auth/config —— 注册/密码策略（免认证） */
export function fetchAuthConfig(): Promise<AuthConfigView> {
  return http.get<AuthConfigView>('/api/auth/config')
}

/** POST /api/auth/register —— 开放注册，返回登录态（免认证） */
export function register(payload: RegisterRequest): Promise<LoginResponse> {
  return http.post<LoginResponse>('/api/auth/register', payload)
}
