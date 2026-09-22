<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'

import { fetchAuthConfig } from '@/api/auth'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

const formRef = ref<FormInstance>()
const loading = ref(false)
const registrationEnabled = ref(false)
const form = reactive({
  username: '',
  password: '',
})

onMounted(async () => {
  // 注册入口是否展示取决于平台策略；读取失败时按「不展示」处理，不阻塞登录
  try {
    const config = await fetchAuthConfig()
    registrationEnabled.value = config.registrationEnabled
  } catch {
    registrationEnabled.value = false
  }
})

const rules: FormRules<typeof form> = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

async function handleSubmit(): Promise<void> {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  loading.value = true
  try {
    await auth.login({ username: form.username.trim(), password: form.password })
    ElMessage.success('登录成功')
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/'
    await router.replace(redirect)
  } catch {
    // 错误提示已由 axios 响应拦截器统一处理
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <el-card class="login-card" shadow="always">
      <h1 class="login-title">智由 AI 视频任务平台</h1>
      <p class="login-subtitle">登录以提交与管理视频生成任务</p>

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        @submit.prevent="handleSubmit"
      >
        <el-form-item label="用户名" prop="username">
          <el-input
            v-model="form.username"
            placeholder="请输入用户名"
            autocomplete="username"
            @keyup.enter="handleSubmit"
          />
        </el-form-item>

        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="请输入密码"
            show-password
            autocomplete="current-password"
            @keyup.enter="handleSubmit"
          />
        </el-form-item>

        <el-form-item>
          <el-button type="primary" :loading="loading" style="width: 100%" @click="handleSubmit">
            登录
          </el-button>
        </el-form-item>
      </el-form>

      <div v-if="registrationEnabled" class="login-footer">
        没有账号？
        <router-link to="/register">立即注册</router-link>
      </div>

      <div class="seed-accounts">
        开发种子账号：<br />
        <code>admin / admin123</code>（管理员）<br />
        <code>user / user123</code>（普通用户）
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.login-footer {
  margin-top: 4px;
  text-align: center;
  font-size: 13px;
  color: #909399;
}

.login-footer a {
  color: var(--el-color-primary);
}
</style>
