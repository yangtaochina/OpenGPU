import { http } from '@/api/client'
import type {
  CreateUserRequest,
  PageResult,
  ResetPasswordRequest,
  UpdateUserRoleRequest,
  UserListQuery,
  UserView,
} from '@/types/api'

/** GET /api/admin/users —— 管理员用户列表（按 createdAt 倒序） */
export function listUsers(params: UserListQuery = {}): Promise<PageResult<UserView>> {
  return http.get<PageResult<UserView>>('/api/admin/users', { params })
}

/** GET /api/admin/users/{id} */
export function getUser(id: string): Promise<UserView> {
  return http.get<UserView>(`/api/admin/users/${id}`)
}

/** POST /api/admin/users —— 管理员直接创建账号（可指定角色） */
export function createUser(payload: CreateUserRequest): Promise<UserView> {
  return http.post<UserView>('/api/admin/users', payload)
}

/** POST /api/admin/users/{id}/enable */
export function enableUser(id: string): Promise<UserView> {
  return http.post<UserView>(`/api/admin/users/${id}/enable`)
}

/** POST /api/admin/users/{id}/disable —— 受保护操作，可能返回 40302 */
export function disableUser(id: string): Promise<UserView> {
  return http.post<UserView>(`/api/admin/users/${id}/disable`)
}

/** POST /api/admin/users/{id}/role —— 受保护操作，可能返回 40302 */
export function updateUserRole(id: string, payload: UpdateUserRoleRequest): Promise<UserView> {
  return http.post<UserView>(`/api/admin/users/${id}/role`, payload)
}

/** POST /api/admin/users/{id}/reset-password */
export function resetUserPassword(id: string, payload: ResetPasswordRequest): Promise<UserView> {
  return http.post<UserView>(`/api/admin/users/${id}/reset-password`, payload)
}
