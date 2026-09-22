<script setup lang="ts">
import { useRouter } from 'vue-router'
import { ElButton } from 'element-plus'

const router = useRouter()
</script>

<template>
  <div class="not-found">
    <h1 style="margin: 0; font-size: 72px">404</h1>
    <p class="text-muted">页面不存在或已被移除</p>
    <ElButton type="primary" @click="router.replace('/')">返回首页</ElButton>
  </div>
</template>
