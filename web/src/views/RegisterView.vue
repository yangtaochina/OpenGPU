<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { isAxiosError } from 'axios'

import { fetchAuthConfig } from '@/api/auth'
import { useAuthStore } from '@/stores/auth'
import type { AuthConfigView } from '@/types/api'

const auth = useAuthStore()
const router = useRouter()

/** 用户名允许字符：字母、数字、下划线、连字符（§2 POST /api/auth/register） */
const USERNAME_PATTERN = /^[A-Za-z0-9_-]+$/

const loadingConfig = ref(true)
const configLoadFailed = ref(false)
const config = ref<AuthConfigView | null>(null)

const formRef = ref<FormInstance>()
const submitting = ref(false)
const form = reactive({
  username: '',
  password: '',
  confirmPassword: '',
})

const serverErrors = reactive({
  username: '',
  password: '',
  confirmPassword: '',
})

function clearServerErrors(): void {
  serverErrors.username = ''
  serverErrors.password = ''
  serverErrors.confirmPassword = ''
}

/** 校验规则完全来自 GET /api/auth/config，前端不硬编码长度 */
const rules = computed<FormRules<typeof form>>(() => {
  const base: FormRules<typeof form> = {
    username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
    password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
    confirmPassword: [{ required: true, message: '请再次输入密码', trigger: 'blur' }],
  }

  const current = config.value
  if (current) {
    base.username = [
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
    base.password = [
      { required: true, message: '请输入密码', trigger: 'blur' },
      {
        min: current.passwordMinLength,
        max: current.passwordMaxLength,
        message: `密码长度需为 ${current.passwordMinLength}–${current.passwordMaxLength} 位`,
        trigger: 'blur',
      },
    ]
  }

  base.confirmPassword = [
    { required: true, message: '请再次输入密码', trigger: 'blur' },
    {
      validator: (_rule: unknown, value: string, callback: (error?: Error) => void) => {
        if (!value) {
          callback()
          return
        }
        if (value !== form.password) {
          callback(new Error('两次输入的密码不一致'))
          return
        }
        callback()
      },
      trigger: 'blur',
    },
  ]

  return base
})

interface ApiErrorInfo {
  code: number | null
  message: string
}

/** 拦截器已统一弹出 message，这里额外把错误定位到表单字段 */
function extractApiError(error: unknown): ApiErrorInfo {
  if (isAxiosError(error)) {
    const payload = error.response?.data as { code?: number; message?: string } | undefined
    return { code: payload?.code ?? null, message: payload?.message ?? error.message }
  }
  if (error instanceof Error) {
    return { code: null, message: error.message }
  }
  return { code: null, message: '注册失败，请稍后重试' }
}

function applyServerError(error: unknown): void {
  const { code, message } = extractApiError(error)
  if (code === 40930) {
    serverErrors.username = message || '用户名已被占用'
    return
  }
  if (code === 40003) {
    if (/密码|password/i.test(message)) {
      serverErrors.password = message
    } else {
      serverErrors.username = message
    }
    return
  }
  if (code === 40301 && config.value) {
    // 注册功能已被关闭：直接切换到未开放提示
    config.value = { ...config.value, registrationEnabled: false }
  }
}

async function handleSubmit(): Promise<void> {
  if (!formRef.value) return
  clearServerErrors()
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  submitting.value = true
  try {
    await auth.register({ username: form.username.trim(), password: form.password })
    ElMessage.success('注册成功')
    await router.replace('/')
  } catch (error) {
    applyServerError(error)
  } finally {
    submitting.value = false
  }
}

async function loadConfig(): Promise<void> {
  loadingConfig.value = true
  configLoadFailed.value = false
  try {
    config.value = await fetchAuthConfig()
  } catch {
    configLoadFailed.value = true
  } finally {
    loadingConfig.value = false
  }
}

onMounted(() => {
  void loadConfig()
})
</script>

<template>
  <div class="login-page">
    <el-card class="login-card" shadow="always">
      <h1 class="login-title">注册账号</h1>
      <p class="login-subtitle">注册后将自动登录智由 AI 视频任务平台</p>

      <div v-if="loadingConfig" v-loading="true" style="height: 180px" />

      <el-result
        v-else-if="configLoadFailed"
        icon="warning"
        title="无法获取注册配置"
        sub-title="请检查网络后重试，或返回登录页。"
      >
        <template #extra>
          <el-button type="primary" @click="loadConfig">重试</el-button>
          <router-link to="/login">
            <el-button>返回登录</el-button>
          </router-link>
        </template>
      </el-result>

      <el-result
        v-else-if="config && !config.registrationEnabled"
        icon="info"
        title="平台当前未开放注册"
        sub-title="如需账号，请联系平台管理员。"
      >
        <template #extra>
          <router-link to="/login">
            <el-button type="primary">返回登录</el-button>
          </router-link>
        </template>
      </el-result>

      <template v-else>
        <el-form
          ref="formRef"
          :model="form"
          :rules="rules"
          label-position="top"
          @submit.prevent="handleSubmit"
        >
          <el-form-item label="用户名" prop="username" :error="serverErrors.username">
            <el-input
              v-model="form.username"
              placeholder="字母、数字、下划线、连字符"
              autocomplete="username"
              @input="serverErrors.username = ''"
              @keyup.enter="handleSubmit"
            />
          </el-form-item>

          <el-form-item label="密码" prop="password" :error="serverErrors.password">
            <el-input
              v-model="form.password"
              type="password"
              placeholder="请输入密码"
              show-password
              autocomplete="new-password"
              @input="serverErrors.password = ''"
              @keyup.enter="handleSubmit"
            />
          </el-form-item>

          <el-form-item label="确认密码" prop="confirmPassword" :error="serverErrors.confirmPassword">
            <el-input
              v-model="form.confirmPassword"
              type="password"
              placeholder="请再次输入密码"
              show-password
              autocomplete="new-password"
              @input="serverErrors.confirmPassword = ''"
              @keyup.enter="handleSubmit"
            />
          </el-form-item>

          <el-form-item>
            <el-button type="primary" :loading="submitting" style="width: 100%" @click="handleSubmit">
              注册
            </el-button>
          </el-form-item>
        </el-form>

        <div class="register-footer">
          已有账号？
          <router-link to="/login">去登录</router-link>
        </div>
      </template>
    </el-card>
  </div>
</template>

<style scoped>
.register-footer {
  margin-top: 12px;
  text-align: center;
  font-size: 13px;
  color: #909399;
}

.register-footer a {
  color: var(--el-color-primary);
}
</style>
