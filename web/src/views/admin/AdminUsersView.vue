<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { isAxiosError } from 'axios'

import { fetchAuthConfig, fetchMe } from '@/api/auth'
import {
  createUser,
  disableUser,
  enableUser,
  listUsers,
  resetUserPassword,
  updateUserRole,
} from '@/api/users'
import { useAuthStore } from '@/stores/auth'
import type { AuthConfigView, CreateUserRequest, UserRole, UserView } from '@/types/api'
import { formatDateTime, formatRelativeTime } from '@/utils/format'
import { USER_ROLE_OPTIONS, userRoleMeta } from '@/utils/status'

const POLL_INTERVAL = 5000

/** 用户名允许字符：字母、数字、下划线、连字符（与 §2 注册规则一致） */
const USERNAME_PATTERN = /^[A-Za-z0-9_-]+$/

const auth = useAuthStore()

const loading = ref(false)
const users = ref<UserView[]>([])
const total = ref(0)
const currentUserId = ref('')
const config = ref<AuthConfigView | null>(null)

const query = reactive({
  page: 1,
  size: 10,
  keyword: '',
  role: '' as UserRole | '',
  enabled: '' as boolean | '',
})

let pollTimer: number | undefined

function stopPolling(): void {
  if (pollTimer !== undefined) {
    window.clearInterval(pollTimer)
    pollTimer = undefined
  }
}

function startPolling(): void {
  stopPolling()
  pollTimer = window.setInterval(() => {
    void loadUsers(true)
  }, POLL_INTERVAL)
}

/** 当前登录用户自己的那一行（用于禁用受保护操作） */
function isSelf(row: UserView): boolean {
  if (currentUserId.value) return row.id === currentUserId.value
  return row.username === auth.username
}

async function loadUsers(silent = false): Promise<void> {
  if (!silent) loading.value = true
  try {
    const page = await listUsers({
      page: query.page - 1,
      size: query.size,
      keyword: query.keyword.trim() || undefined,
      role: query.role || undefined,
      enabled: query.enabled === '' ? undefined : query.enabled,
    })
    users.value = page.content ?? []
    total.value = page.totalElements ?? 0
  } catch {
    // 错误提示已由拦截器处理
  } finally {
    if (!silent) loading.value = false
  }
}

async function loadConfig(): Promise<void> {
  try {
    config.value = await fetchAuthConfig()
  } catch {
    // 配置读取失败时退化为仅做必填校验，长度交由服务端把关
    config.value = null
  }
}

async function loadCurrentUser(): Promise<void> {
  try {
    currentUserId.value = (await fetchMe()).id
  } catch {
    // 取不到 id 时用用户名兜底判断「自己」
    currentUserId.value = ''
  }
}

function handleSearch(): void {
  query.page = 1
  void loadUsers()
}

function handleReset(): void {
  query.keyword = ''
  query.role = ''
  query.enabled = ''
  query.page = 1
  void loadUsers()
}

function handlePageChange(page: number): void {
  query.page = page
  void loadUsers()
}

function handleSizeChange(size: number): void {
  query.size = size
  query.page = 1
  void loadUsers()
}

interface ApiErrorInfo {
  code: number | null
  message: string
}

function extractApiError(error: unknown): ApiErrorInfo {
  if (isAxiosError(error)) {
    const payload = error.response?.data as { code?: number; message?: string } | undefined
    return { code: payload?.code ?? null, message: payload?.message ?? error.message }
  }
  if (error instanceof Error) {
    return { code: null, message: error.message }
  }
  return { code: null, message: '操作失败，请稍后重试' }
}

/**
 * §4.5 受保护操作（40302）：例如「不能停用最后一个启用的管理员」。
 * 判断由服务端负责，前端只负责展示后端返回的 message。
 */
function handleProtectedError(error: unknown): void {
  const { code, message } = extractApiError(error)
  if (code === 40302) {
    // 响应拦截器已弹过一次，这里先关闭再展示，避免重复提示
    ElMessage.closeAll()
    ElMessage.error(message || '受保护的管理操作被拒绝')
  }
}

// ---------- 新建用户 ----------

const createDialogVisible = ref(false)
const creating = ref(false)
const createFormRef = ref<FormInstance>()
const createForm = reactive<CreateUserRequest>({
  username: '',
  password: '',
  role: 'USER',
})

const createRules = computed<FormRules<typeof createForm>>(() => {
  const rules: FormRules<typeof createForm> = {
    username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
    password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
  }
  const current = config.value
  if (current) {
    rules.username = [
      { required: true, message: '请输入用户名', trigger: 'blur' },
      {
        min: current.usernameMinLength,
        max: current.usernameMaxLength,
        message: `用户名长度需为 ${current.usernameMinLength}–${current.usernameMaxLength} 位`,
        trigger: 'blur',
      },
      {
        pattern: USERNAME_PATTERN,
        message: '用户名仅允许字母、数字、下划线、连字符',
        trigger: 'blur',
      },
    ]
    rules.password = [
      { required: true, message: '请输入密码', trigger: 'blur' },
      {
        min: current.passwordMinLength,
        max: current.passwordMaxLength,
        message: `密码长度需为 ${current.passwordMinLength}–${current.passwordMaxLength} 位`,
        trigger: 'blur',
      },
    ]
  }
  return rules
})

function openCreateDialog(): void {
  createDialogVisible.value = true
  createForm.username = ''
  createForm.password = ''
  createForm.role = 'USER'
  createFormRef.value?.clearValidate()
}

async function handleCreate(): Promise<void> {
  if (!createFormRef.value) return
  const valid = await createFormRef.value.validate().catch(() => false)
  if (!valid) return

  creating.value = true
  try {
    await createUser({
      username: createForm.username.trim(),
      password: createForm.password,
      role: createForm.role,
    })
    createDialogVisible.value = false
    ElMessage.success('用户已创建')
    await loadUsers()
  } catch {
    // 错误提示已由拦截器处理
  } finally {
    creating.value = false
  }
}

// ---------- 启用 / 停用 ----------

const operating = ref('')

async function handleToggle(row: UserView): Promise<void> {
  const action = row.enabled ? '停用' : '启用'
  try {
    await ElMessageBox.confirm(
      `确认${action}用户「${row.username}」吗？${row.enabled ? '停用后该用户将无法登录。' : '启用后该用户可正常登录。'}`,
      `${action}用户`,
      { type: 'warning', confirmButtonText: `确认${action}`, cancelButtonText: '返回' },
    )
  } catch {
    return
  }
  operating.value = row.id
  try {
    if (row.enabled) {
      await disableUser(row.id)
    } else {
      await enableUser(row.id)
    }
    ElMessage.success(`用户已${action}`)
    await loadUsers()
  } catch (error) {
    handleProtectedError(error)
  } finally {
    operating.value = ''
  }
}

// ---------- 修改角色 ----------

const roleDialogVisible = ref(false)
const roleSubmitting = ref(false)
const roleTarget = ref<UserView | null>(null)
const roleForm = reactive<{ role: UserRole }>({ role: 'USER' })

function openRoleDialog(row: UserView): void {
  roleTarget.value = row
  roleForm.role = row.role
  roleDialogVisible.value = true
}

async function handleUpdateRole(): Promise<void> {
  if (!roleTarget.value) return
  roleSubmitting.value = true
  try {
    await updateUserRole(roleTarget.value.id, { role: roleForm.role })
    roleDialogVisible.value = false
    ElMessage.success('角色已更新')
    await loadUsers()
  } catch (error) {
    handleProtectedError(error)
  } finally {
    roleSubmitting.value = false
  }
}

// ---------- 重置密码 ----------

const resetDialogVisible = ref(false)
const resetSubmitting = ref(false)
const resetFormRef = ref<FormInstance>()
const resetTarget = ref<UserView | null>(null)
const resetForm = reactive({ newPassword: '' })

const resetRules = computed<FormRules<typeof resetForm>>(() => {
  const required = { required: true, message: '请输入新密码', trigger: 'blur' as const }
  const current = config.value
  if (!current) {
    return { newPassword: [required] }
  }
  return {
    newPassword: [
      required,
      {
        min: current.passwordMinLength,
        max: current.passwordMaxLength,
        message: `密码长度需为 ${current.passwordMinLength}–${current.passwordMaxLength} 位`,
        trigger: 'blur',
      },
    ],
  }
})

function openResetDialog(row: UserView): void {
  resetTarget.value = row
  resetForm.newPassword = ''
  resetDialogVisible.value = true
  resetFormRef.value?.clearValidate()
}

async function handleResetPassword(): Promise<void> {
  if (!resetFormRef.value || !resetTarget.value) return
  const valid = await resetFormRef.value.validate().catch(() => false)
  if (!valid) return

  resetSubmitting.value = true
  try {
    await resetUserPassword(resetTarget.value.id, { newPassword: resetForm.newPassword })
    resetDialogVisible.value = false
    ElMessage.success('密码已重置')
  } catch {
    // 错误提示已由拦截器处理
  } finally {
    resetSubmitting.value = false
  }
}

onMounted(() => {
  void loadUsers()
  void loadConfig()
  void loadCurrentUser()
  startPolling()
})

onUnmounted(() => {
  stopPolling()
})
</script>

<template>
  <div class="page-container">
    <div class="page-header">
      <h2>管理端 · 用户</h2>
      <div class="user-filters">
        <el-input
          v-model="query.keyword"
          placeholder="用户名关键字"
          clearable
          style="width: 180px"
          @keyup.enter="handleSearch"
          @clear="handleSearch"
        />
        <el-select v-model="query.role" placeholder="全部角色" style="width: 130px">
          <el-option label="全部" value="" />
          <el-option
            v-for="option in USER_ROLE_OPTIONS"
            :key="option.value"
            :label="option.label"
            :value="option.value"
          />
        </el-select>
        <el-select v-model="query.enabled" placeholder="全部状态" style="width: 130px">
          <el-option label="全部" value="" />
          <el-option label="启用" :value="true" />
          <el-option label="停用" :value="false" />
        </el-select>
        <el-button type="primary" :loading="loading" @click="handleSearch">查询</el-button>
        <el-button @click="handleReset">重置</el-button>
        <el-button type="primary" plain @click="openCreateDialog">新建用户</el-button>
      </div>
    </div>

    <el-card shadow="never">
      <el-table v-loading="loading" :data="users" stripe empty-text="暂无用户">
        <el-table-column label="用户名" min-width="160" prop="username" />

        <el-table-column label="角色" width="100">
          <template #default="{ row }">
            <el-tag :type="userRoleMeta(row.role).tagType" size="small">
              {{ userRoleMeta(row.role).label }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.enabled ? 'success' : 'info'" size="small">
              {{ row.enabled ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="创建时间" width="180">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>

        <el-table-column label="最后登录时间" width="180">
          <template #default="{ row }">
            <el-tooltip
              :content="formatDateTime(row.lastLoginAt)"
              placement="top"
              :disabled="!row.lastLoginAt"
            >
              <span>{{ row.lastLoginAt ? formatRelativeTime(row.lastLoginAt) : '—' }}</span>
            </el-tooltip>
          </template>
        </el-table-column>

        <el-table-column label="操作" min-width="250" fixed="right">
          <template #default="{ row }">
            <el-tooltip
              content="不能停用自己的账号"
              placement="top"
              :disabled="!(isSelf(row) && row.enabled)"
            >
              <span>
                <el-button
                  link
                  :type="row.enabled ? 'danger' : 'success'"
                  :disabled="isSelf(row) && row.enabled"
                  :loading="operating === row.id"
                  @click="handleToggle(row)"
                >
                  {{ row.enabled ? '停用' : '启用' }}
                </el-button>
              </span>
            </el-tooltip>

            <el-tooltip
              content="不能修改自己的账号"
              placement="top"
              :disabled="!isSelf(row)"
            >
              <span>
                <el-button link type="primary" :disabled="isSelf(row)" @click="openRoleDialog(row)">
                  改角色
                </el-button>
              </span>
            </el-tooltip>

            <el-button link type="warning" @click="openResetDialog(row)">重置密码</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        style="margin-top: 16px; justify-content: flex-end"
        layout="total, sizes, prev, pager, next"
        :total="total"
        :current-page="query.page"
        :page-size="query.size"
        :page-sizes="[10, 20, 50]"
        @current-change="handlePageChange"
        @size-change="handleSizeChange"
      />
    </el-card>

    <!-- 新建用户 -->
    <el-dialog v-model="createDialogVisible" title="新建用户" width="460px">
      <el-form
        ref="createFormRef"
        :model="createForm"
        :rules="createRules"
        label-width="90px"
        @submit.prevent="handleCreate"
      >
        <el-form-item label="用户名" prop="username">
          <el-input v-model="createForm.username" placeholder="字母、数字、下划线、连字符" />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input
            v-model="createForm.password"
            type="password"
            placeholder="请输入初始密码"
            show-password
            autocomplete="new-password"
          />
        </el-form-item>
        <el-form-item label="角色" prop="role">
          <el-select v-model="createForm.role" style="width: 100%">
            <el-option
              v-for="option in USER_ROLE_OPTIONS"
              :key="option.value"
              :label="option.label"
              :value="option.value"
            />
          </el-select>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="createDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="handleCreate">创建</el-button>
      </template>
    </el-dialog>

    <!-- 修改角色 -->
    <el-dialog v-model="roleDialogVisible" title="修改角色" width="420px">
      <p>
        <span class="text-muted">用户名：</span>{{ roleTarget?.username }}
      </p>
      <el-form label-width="90px" @submit.prevent="handleUpdateRole">
        <el-form-item label="角色">
          <el-select v-model="roleForm.role" style="width: 100%">
            <el-option
              v-for="option in USER_ROLE_OPTIONS"
              :key="option.value"
              :label="option.label"
              :value="option.value"
            />
          </el-select>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="roleDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="roleSubmitting" @click="handleUpdateRole">
          保存
        </el-button>
      </template>
    </el-dialog>

    <!-- 重置密码 -->
    <el-dialog v-model="resetDialogVisible" title="重置密码" width="420px">
      <p>
        <span class="text-muted">用户名：</span>{{ resetTarget?.username }}
      </p>
      <el-form
        ref="resetFormRef"
        :model="resetForm"
        :rules="resetRules"
        label-width="90px"
        @submit.prevent="handleResetPassword"
      >
        <el-form-item label="新密码" prop="newPassword">
          <el-input
            v-model="resetForm.newPassword"
            type="password"
            placeholder="请输入新密码"
            show-password
            autocomplete="new-password"
          />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="resetDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="resetSubmitting" @click="handleResetPassword">
          确认重置
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.user-filters {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
</style>
