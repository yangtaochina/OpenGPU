<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'

import StatusTag from '@/components/StatusTag.vue'
import { getTask } from '@/api/tasks'
import type { TaskDetailView, TaskStatus } from '@/types/api'
import {
  formatDateTime,
  formatDuration,
  formatFileSize,
  formatRelativeTime,
  formatResolution,
  resolveFileUrl,
} from '@/utils/format'
import { isTerminalTaskStatus, gpuTierMeta } from '@/utils/status'

const props = defineProps<{ id: string }>()
const router = useRouter()

const POLL_INTERVAL = 3000

const loading = ref(false)
const detail = ref<TaskDetailView | null>(null)
const notFound = ref(false)

const task = computed(() => detail.value?.task ?? null)
const attempts = computed(() => detail.value?.attempts ?? [])
const result = computed(() => detail.value?.result ?? task.value?.result ?? null)

const videoUrl = computed(() => resolveFileUrl(result.value?.fileUrl))
const downloadName = computed(() => {
  const key = result.value?.fileKey
  if (!key) return 'result.mp4'
  return key.split('/').pop() || 'result.mp4'
})

// ---------- 状态时间线 ----------

const STEP_LABELS = ['排队中', '已分配', '运行中', '上传中', '已成功']

const STEP_INDEX: Record<TaskStatus, number> = {
  QUEUED: 0,
  ASSIGNED: 1,
  RUNNING: 2,
  UPLOADING: 3,
  SUCCEEDED: 4,
  FAILED: 2,
  CANCELED: 0,
}

const activeStep = computed(() => (task.value ? STEP_INDEX[task.value.status] : 0))

const stepStatus = computed<'wait' | 'process' | 'finish' | 'error' | 'success'>(() => {
  const status = task.value?.status
  if (status === 'FAILED' || status === 'CANCELED') return 'error'
  if (status === 'SUCCEEDED') return 'success'
  return 'process'
})

// ---------- 加载与轮询 ----------

let pollTimer: number | undefined

function stopPolling(): void {
  if (pollTimer !== undefined) {
    window.clearInterval(pollTimer)
    pollTimer = undefined
  }
}

function ensurePolling(): void {
  const status = task.value?.status
  if (status && !isTerminalTaskStatus(status)) {
    if (pollTimer === undefined) {
      pollTimer = window.setInterval(() => {
        void loadDetail(true)
      }, POLL_INTERVAL)
    }
  } else {
    stopPolling()
  }
}

async function loadDetail(silent = false): Promise<void> {
  if (!silent) loading.value = true
  try {
    const data = await getTask(props.id)
    detail.value = data
    notFound.value = false
    ensurePolling()
  } catch {
    if (!silent) {
      notFound.value = true
      detail.value = null
    }
  } finally {
    if (!silent) loading.value = false
  }
}

function handleDownload(): void {
  if (!videoUrl.value) return
  const link = document.createElement('a')
  link.href = videoUrl.value
  link.download = downloadName.value
  link.target = '_blank'
  link.rel = 'noopener'
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  ElMessage.success('已开始下载')
}

watch(
  () => props.id,
  () => {
    stopPolling()
    detail.value = null
    void loadDetail()
  },
)

onMounted(() => {
  void loadDetail()
})

onUnmounted(() => {
  stopPolling()
})
</script>

<template>
  <div class="page-container" v-loading="loading">
    <div class="page-header">
      <h2>任务详情</h2>
      <div>
        <el-button size="small" @click="router.back()">返回</el-button>
        <el-button size="small" type="primary" @click="loadDetail()">刷新</el-button>
      </div>
    </div>

    <el-empty v-if="notFound && !loading" description="任务不存在或已被删除">
      <el-button type="primary" @click="router.push('/')">返回任务列表</el-button>
    </el-empty>

    <template v-else-if="task">
      <el-card class="card" shadow="never">
        <div class="page-header" style="margin-bottom: 12px">
          <span>
            <span class="text-muted">任务号：</span>
            <span class="mono">{{ task.id }}</span>
          </span>
          <StatusTag :status="task.status" kind="task" size="default" />
        </div>

        <el-steps :active="activeStep" :status="stepStatus" align-center finish-status="success">
          <el-step v-for="label in STEP_LABELS" :key="label" :title="label" />
        </el-steps>

        <div style="margin-top: 20px">
          <el-progress
            :percentage="task.progress ?? 0"
            :status="
              task.status === 'FAILED'
                ? 'exception'
                : task.status === 'SUCCEEDED'
                  ? 'success'
                  : undefined
            "
            :stroke-width="16"
          />
        </div>

        <el-alert
          v-if="task.errorCode || task.errorMessage"
          style="margin-top: 16px"
          type="error"
          :closable="false"
          show-icon
          title="任务执行失败"
        >
          <div v-if="task.errorCode">
            <span class="text-muted">错误码：</span><span class="mono">{{ task.errorCode }}</span>
          </div>
          <div v-if="task.errorMessage">
            <span class="text-muted">错误摘要：</span>{{ task.errorMessage }}
          </div>
        </el-alert>

        <el-descriptions :column="2" border style="margin-top: 20px">
          <el-descriptions-item label="重试次数">
            {{ task.retryCount }} / {{ task.maxRetry }}
          </el-descriptions-item>
          <el-descriptions-item label="执行节点">
            {{ task.workerName || '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="租约到期">
            {{ formatDateTime(task.leaseExpiresAt) }}
          </el-descriptions-item>
          <el-descriptions-item label="创建时间">
            {{ formatDateTime(task.createdAt) }}
          </el-descriptions-item>
          <el-descriptions-item label="开始时间">
            {{ formatDateTime(task.startedAt) }}
          </el-descriptions-item>
          <el-descriptions-item label="结束时间">
            {{ formatDateTime(task.finishedAt) }}
          </el-descriptions-item>
          <el-descriptions-item label="更新时间">
            {{ formatDateTime(task.updatedAt) }}
          </el-descriptions-item>
        </el-descriptions>
      </el-card>

      <el-card class="card" shadow="never">
        <template #header><span style="font-weight: 600">完整提示词</span></template>
        <div class="prompt-block">{{ task.prompt }}</div>
      </el-card>

      <el-card class="card" shadow="never">
        <template #header><span style="font-weight: 600">视频要求</span></template>

        <template v-if="task.requirement">
          <div class="requirement-summary">{{ task.requirement.summary }}</div>
          <el-descriptions :column="2" border style="margin-top: 12px">
            <el-descriptions-item label="所需显存">
              {{ task.requirement.requiredVramMb }} MB
            </el-descriptions-item>
            <el-descriptions-item label="显卡档位">
              <el-tag :type="gpuTierMeta(task.requirement.gpuTier).tagType" size="small">
                {{ gpuTierMeta(task.requirement.gpuTier).label }}
              </el-tag>
            </el-descriptions-item>
          </el-descriptions>
        </template>

        <div v-else class="text-muted">不限定硬件要求</div>
      </el-card>

      <el-card class="card" shadow="never">
        <template #header><span style="font-weight: 600">尝试历史</span></template>
        <el-table :data="attempts" stripe empty-text="暂无尝试记录">
          <el-table-column label="次数" prop="attemptNo" width="70" />
          <el-table-column label="节点" min-width="140">
            <template #default="{ row }">{{ row.workerName || '—' }}</template>
          </el-table-column>
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <StatusTag :status="row.status" kind="attempt" />
            </template>
          </el-table-column>
          <el-table-column label="错误码" min-width="120">
            <template #default="{ row }">
              <span v-if="row.errorCode" class="mono">{{ row.errorCode }}</span>
              <span v-else class="text-muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="错误摘要" min-width="180">
            <template #default="{ row }">
              <span v-if="row.errorMessage">{{ row.errorMessage }}</span>
              <span v-else class="text-muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="开始时间" width="180">
            <template #default="{ row }">{{ formatDateTime(row.startedAt) }}</template>
          </el-table-column>
          <el-table-column label="结束时间" width="180">
            <template #default="{ row }">{{ formatDateTime(row.endedAt) }}</template>
          </el-table-column>
        </el-table>
      </el-card>

      <el-card class="card" shadow="never">
        <template #header><span style="font-weight: 600">结果视频</span></template>

        <template v-if="result && videoUrl">
          <video class="result-video" :src="videoUrl" controls preload="metadata" />
          <el-descriptions :column="2" border style="margin-top: 16px">
            <el-descriptions-item label="文件大小">
              {{ formatFileSize(result.fileSize) }}
            </el-descriptions-item>
            <el-descriptions-item label="时长">
              {{ formatDuration(result.durationSeconds) }}
            </el-descriptions-item>
            <el-descriptions-item label="分辨率">
              {{ formatResolution(result.width, result.height) }}
            </el-descriptions-item>
            <el-descriptions-item label="生成时间">
              {{ formatDateTime(result.createdAt) }}（{{ formatRelativeTime(result.createdAt) }}）
            </el-descriptions-item>
          </el-descriptions>
          <el-button type="primary" style="margin-top: 16px" @click="handleDownload">
            下载视频
          </el-button>
        </template>

        <el-empty v-else description="任务尚未生成结果视频" />
      </el-card>
    </template>
  </div>
</template>

<style scoped>
.requirement-summary {
  color: var(--el-text-color-regular);
}
</style>
