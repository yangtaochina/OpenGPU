<script setup lang="ts">
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'

import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const router = useRouter()

function handleLogout(): void {
  auth.logout()
  ElMessage.success('已退出登录')
  void router.push({ path: '/login' })
}
</script>

<template>
  <div class="app-shell">
    <header class="app-header">
      <span class="brand">智由 AI 视频任务平台</span>

      <nav class="app-nav">
        <router-link to="/">提交任务</router-link>
        <router-link :to="{ path: '/', hash: '#my-tasks' }">我的任务</router-link>
        <template v-if="auth.isAdmin">
          <router-link to="/admin/tasks">管理端 · 任务</router-link>
          <router-link to="/admin/workers">管理端 · 节点</router-link>
          <router-link to="/admin/users">管理端 · 用户</router-link>
        </template>
      </nav>

      <div class="app-user">
        <span class="username">{{ auth.username || '未登录' }}</span>
        <el-tag v-if="auth.isAdmin" type="danger" size="small" effect="dark">管理员</el-tag>
        <el-tag v-else type="info" size="small" effect="dark">用户</el-tag>
        <el-button link type="primary" @click="handleLogout">退出登录</el-button>
      </div>
    </header>

    <main class="app-main">
      <router-view />
    </main>
  </div>
</template>
