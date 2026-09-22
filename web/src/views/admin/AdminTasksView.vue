<script setup lang="ts">
import { onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'

import StatusTag from '@/components/StatusTag.vue'
import { listAdminTasks, retryTask } from '@/api/tasks'
import type { TaskStatus, TaskView } from '@/types/api'
import { formatDateTime, shortId, summarizePrompt } from '@/utils/format'
import { TASK_STATUS_OPTIONS, isTerminalTaskStatus } from '@/utils/status'

const router = useRouter()

const POLL_INTERVAL = 3000

const loading = ref(false)
const retrying = ref<string>('')
const tasks = ref<TaskView[]>([])
const total = ref(0)
const query = reactive({
  page: 1,
  size: 10,
  status: '' as TaskStatus | '',
})

let pollTimer: number | undefined

function stopPolling(): void {
  if (pollTimer !== undefined) {
    window.clearInterval(pollTimer)
    pollTimer = undefined
  }
}

function ensurePolling(): void {
  const hasActive = tasks.value.some((task) => !isTerminalTaskStatus(task.status))
  if (hasActive && pollTimer === undefined) {
    pollTimer = window.setInterval(() => {
      void loadTasks(true)
    }, POLL_INTERVAL)
  } else if (!hasActive) {
    stopPolling()
  }
}

async function loadTasks(silent = false): Promise<void> {
  if (!silent) loading.value = true
  try {
    const page = await listAdminTasks({
      page: query.page - 1,
      size: query.size,
      status: query.status || undefined,
    })
    tasks.value = page.content ?? []
    total.value = page.totalElements ?? 0
    ensurePolling()
  } catch {
    // 错误提示已由拦截器处理
  } finally {
    if (!silent) loading.value = false
  }
}

function handleStatusChange(): void {
  query.page = 1
  void loadTasks()
}

function handlePageChange(page: number): void {
  query.page = page
  void loadTasks()
}

function handleSizeChange(size: number): void {
  query.size = size
  query.page = 1
  void loadTasks()
}

function goDetail(id: string): void {
  void router.push({ name: 'task-detail', params: { id } })
}

async function handleRetry(task: TaskView): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确认重试任务 ${shortId(task.id)} 吗？重试将重置重试次数并回到排队状态。`,
      '重试任务',
      { type: 'warning', confirmButtonText: '确认重试', cancelButtonText: '返回' },
    )
  } catch {
    return
  }
  retrying.value = task.id
  try {
    await retryTask(task.id)
    ElMessage.success('已提交重试')
    await loadTasks()
  } catch {
    // 40900 等错误已由拦截器提示
  } finally {
    retrying.value = ''
  }
}

function progressStatus(task: TaskView): 'success' | 'exception' | 'warning' | undefined {
  if (task.status === 'FAILED') return 'exception'
  if (task.status === 'SUCCEEDED') return 'success'
  if (task.status === 'CANCELED') return 'warning'
  return undefined
}

onMounted(() => {
  void loadTasks()
})

onUnmounted(() => {
  stopPolling()
})
</script>

<template>
  <div class="page-container">
    <div class="page-header">
      <h2>管理端 · 任务列表</h2>
      <div>
        <el-select
          v-model="query.status"
          placeholder="全部状态"
          clearable
          style="width: 160px; margin-right: 8px"
          @change="handleStatusChange"
        >
          <el-option
            v-for="option in TASK_STATUS_OPTIONS"
            :key="option.value"
            :label="option.label"
            :value="option.value"
          />
        </el-select>
        <el-button :loading="loading" @click="loadTasks()">刷新</el-button>
      </div>
    </div>

    <el-card shadow="never">
      <el-table v-loading="loading" :data="tasks" stripe empty-text="暂无任务">
        <el-table-column label="任务号" width="120">
          <template #default="{ row }">
            <span class="mono">{{ shortId(row.id) }}</span>
          </template>
        </el-table-column>

        <el-table-column label="提示词摘要" min-width="200">
          <template #default="{ row }">
            <el-tooltip :content="row.prompt" placement="top" :disabled="!row.prompt">
              <span class="text-ellipsis">{{ summarizePrompt(row.prompt) }}</span>
            </el-tooltip>
          </template>
        </el-table-column>

        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <StatusTag :status="row.status" kind="task" />
          </template>
        </el-table-column>

        <el-table-column label="进度" width="160">
          <template #default="{ row }">
            <el-progress
              :percentage="row.progress ?? 0"
              :status="progressStatus(row)"
              :stroke-width="12"
            />
          </template>
        </el-table-column>

        <el-table-column label="执行节点" min-width="140">
          <template #default="{ row }">{{ row.workerName || '—' }}</template>
        </el-table-column>

        <el-table-column label="重试" width="80">
          <template #default="{ row }">{{ row.retryCount }} / {{ row.maxRetry }}</template>
        </el-table-column>

        <el-table-column label="创建时间" width="180">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>

        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="goDetail(row.id)">查看</el-button>
            <el-button
              link
              type="warning"
              :disabled="row.status !== 'FAILED'"
              :loading="retrying === row.id"
              @click="handleRetry(row)"
            >
              重试
            </el-button>
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
  </div>
</template>
