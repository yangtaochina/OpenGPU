<script setup lang="ts">
import { onMounted, onUnmounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'

import StatusTag from '@/components/StatusTag.vue'
import {
  createWorker,
  disableWorker,
  enableWorker,
  fetchOverview,
  listWorkers,
} from '@/api/workers'
import type { AdminOverviewView, CreateWorkerRequest, WorkerStatus, WorkerView } from '@/types/api'
import { formatDateTime, formatRelativeTime, shortId } from '@/utils/format'
import { WORKER_STATUS_OPTIONS, GPU_TIER_OPTIONS, gpuTierMeta } from '@/utils/status'

const POLL_INTERVAL = 5000

const loading = ref(false)
const workers = ref<WorkerView[]>([])
const total = ref(0)
const overview = ref<AdminOverviewView | null>(null)
const query = reactive({
  page: 1,
  size: 10,
  status: '' as WorkerStatus | '',
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
    void loadWorkers(true)
  }, POLL_INTERVAL)
}

async function loadWorkers(silent = false): Promise<void> {
  if (!silent) loading.value = true
  try {
    const [page, overviewData] = await Promise.all([
      listWorkers({
        page: query.page - 1,
        size: query.size,
        status: query.status || undefined,
      }),
      fetchOverview().catch(() => null),
    ])
    workers.value = page.content ?? []
    total.value = page.totalElements ?? 0
    if (overviewData) overview.value = overviewData
  } catch {
    // 错误提示已由拦截器处理
  } finally {
    if (!silent) loading.value = false
  }
}

function handleStatusChange(): void {
  query.page = 1
  void loadWorkers()
}

function handlePageChange(page: number): void {
  query.page = page
  void loadWorkers()
}

function handleSizeChange(size: number): void {
  query.size = size
  query.page = 1
  void loadWorkers()
}

// ---------- 新建节点 ----------

const createDialogVisible = ref(false)
const creating = ref(false)
const createFormRef = ref<FormInstance>()
const createForm = reactive<CreateWorkerRequest>({
  name: '',
  gpuModel: '',
  vramMb: undefined,
  workerVersion: '',
  modelVersion: '',
  gpuTier: undefined,
  maxDurationSeconds: undefined,
  supportedResolutions: '',
})

const createRules: FormRules<typeof createForm> = {
  name: [{ required: true, message: '请输入节点名称', trigger: 'blur' }],
}

function openCreateDialog(): void {
  createDialogVisible.value = true
  createForm.name = ''
  createForm.gpuModel = ''
  createForm.vramMb = undefined
  createForm.workerVersion = ''
  createForm.modelVersion = ''
  createForm.gpuTier = undefined
  createForm.maxDurationSeconds = undefined
  createForm.supportedResolutions = ''
  createFormRef.value?.clearValidate()
}

async function handleCreate(): Promise<void> {
  if (!createFormRef.value) return
  const valid = await createFormRef.value.validate().catch(() => false)
  if (!valid) return

  creating.value = true
  try {
    const response = await createWorker({
      name: createForm.name.trim(),
      gpuModel: createForm.gpuModel?.trim() || undefined,
      vramMb: createForm.vramMb,
      workerVersion: createForm.workerVersion?.trim() || undefined,
      modelVersion: createForm.modelVersion?.trim() || undefined,
      gpuTier: createForm.gpuTier,
      maxDurationSeconds: createForm.maxDurationSeconds,
      supportedResolutions: createForm.supportedResolutions?.trim() || undefined,
    })
    createDialogVisible.value = false
    showTokenDialog(response.worker, response.token)
    await loadWorkers()
  } catch {
    // 错误提示已由拦截器处理
  } finally {
    creating.value = false
  }
}

// ---------- token 一次性展示 ----------

const tokenDialogVisible = ref(false)
const tokenWorker = ref<WorkerView | null>(null)
const issuedToken = ref('')

function showTokenDialog(worker: WorkerView, token: string): void {
  tokenWorker.value = worker
  issuedToken.value = token
  tokenDialogVisible.value = true
}

async function copyToken(): Promise<void> {
  if (!issuedToken.value) return
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(issuedToken.value)
    } else {
      const textarea = document.createElement('textarea')
      textarea.value = issuedToken.value
      textarea.style.position = 'fixed'
      textarea.style.opacity = '0'
      document.body.appendChild(textarea)
      textarea.select()
      document.execCommand('copy')
      document.body.removeChild(textarea)
    }
    ElMessage.success('Token 已复制到剪贴板')
  } catch {
    ElMessage.error('复制失败，请手动选择并复制')
  }
}

// ---------- 启用 / 停用 ----------

const operating = ref<string>('')

async function handleToggle(worker: WorkerView): Promise<void> {
  const action = worker.enabled ? '停用' : '启用'
  try {
    await ElMessageBox.confirm(
      `确认${action}节点「${worker.name}」吗？${worker.enabled ? '停用后节点不能领取新任务。' : '启用后节点恢复为在线。'}`,
      `${action}节点`,
      { type: 'warning', confirmButtonText: `确认${action}`, cancelButtonText: '返回' },
    )
  } catch {
    return
  }
  operating.value = worker.id
  try {
    if (worker.enabled) {
      await disableWorker(worker.id)
    } else {
      await enableWorker(worker.id)
    }
    ElMessage.success(`节点已${action}`)
    await loadWorkers()
  } catch {
    // 错误提示已由拦截器处理
  } finally {
    operating.value = ''
  }
}

onMounted(() => {
  void loadWorkers()
  startPolling()
})

onUnmounted(() => {
  stopPolling()
})
</script>

<template>
  <div class="page-container">
    <div class="page-header">
      <h2>管理端 · 节点列表</h2>
      <div>
        <el-select
          v-model="query.status"
          placeholder="全部状态"
          clearable
          style="width: 160px; margin-right: 8px"
          @change="handleStatusChange"
        >
          <el-option
            v-for="option in WORKER_STATUS_OPTIONS"
            :key="option.value"
            :label="option.label"
            :value="option.value"
          />
        </el-select>
        <el-button :loading="loading" @click="loadWorkers()">刷新</el-button>
        <el-button type="primary" @click="openCreateDialog">新建节点</el-button>
      </div>
    </div>

    <el-row v-if="overview" :gutter="12" style="margin-bottom: 16px">
      <el-col :span="4">
        <el-card shadow="never">
          <div class="text-muted">任务总数</div>
          <div style="font-size: 22px; font-weight: 600">{{ overview.taskTotal }}</div>
        </el-card>
      </el-col>
      <el-col :span="4">
        <el-card shadow="never">
          <div class="text-muted">排队中</div>
          <div style="font-size: 22px; font-weight: 600">{{ overview.taskQueued }}</div>
        </el-card>
      </el-col>
      <el-col :span="4">
        <el-card shadow="never">
          <div class="text-muted">运行中</div>
          <div style="font-size: 22px; font-weight: 600">{{ overview.taskRunning }}</div>
        </el-card>
      </el-col>
      <el-col :span="4">
        <el-card shadow="never">
          <div class="text-muted">已成功</div>
          <div style="font-size: 22px; font-weight: 600">{{ overview.taskSucceeded }}</div>
        </el-card>
      </el-col>
      <el-col :span="4">
        <el-card shadow="never">
          <div class="text-muted">失败</div>
          <div style="font-size: 22px; font-weight: 600">{{ overview.taskFailed }}</div>
        </el-card>
      </el-col>
      <el-col :span="4">
        <el-card shadow="never">
          <div class="text-muted">节点 在线/离线/停用</div>
          <div style="font-size: 22px; font-weight: 600">
            {{ overview.workerOnline }}/{{ overview.workerOffline }}/{{
              overview.workerDisabled
            }}
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never">
      <el-table v-loading="loading" :data="workers" stripe empty-text="暂无节点">
        <el-table-column label="名称" min-width="140" prop="name" />

        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <StatusTag :status="row.status" kind="worker" />
          </template>
        </el-table-column>

        <el-table-column label="运行状态" width="100">
          <template #default="{ row }">
            <StatusTag :status="row.runtimeStatus" kind="runtime" />
          </template>
        </el-table-column>

        <el-table-column label="GPU 型号" min-width="120">
          <template #default="{ row }">{{ row.gpuModel || '—' }}</template>
        </el-table-column>

        <el-table-column label="显存 (MB)" width="110">
          <template #default="{ row }">
            <el-tooltip
              v-if="row.vramMb == null"
              content="未上报显存，只能领取不限硬件的任务"
              placement="top"
            >
              <span class="text-muted">—</span>
            </el-tooltip>
            <span v-else>{{ row.vramMb }}</span>
          </template>
        </el-table-column>

        <el-table-column label="显卡档位" width="100">
          <template #default="{ row }">
            <el-tag v-if="row.gpuTier" :type="gpuTierMeta(row.gpuTier).tagType" size="small">
              {{ gpuTierMeta(row.gpuTier).label }}
            </el-tag>
            <span v-else class="text-muted">—</span>
          </template>
        </el-table-column>

        <el-table-column label="最长时长" width="100">
          <template #default="{ row }">
            {{ row.maxDurationSeconds != null ? `${row.maxDurationSeconds} 秒` : '—' }}
          </template>
        </el-table-column>

        <el-table-column label="支持分辨率" min-width="150">
          <template #default="{ row }">{{ row.supportedResolutions || '—' }}</template>
        </el-table-column>

        <el-table-column label="Worker 版本" width="120">
          <template #default="{ row }">{{ row.workerVersion || '—' }}</template>
        </el-table-column>

        <el-table-column label="模型版本" width="130">
          <template #default="{ row }">{{ row.modelVersion || '—' }}</template>
        </el-table-column>

        <el-table-column label="当前任务" width="120">
          <template #default="{ row }">
            <span v-if="row.currentTaskId" class="mono">{{ shortId(row.currentTaskId) }}</span>
            <span v-else class="text-muted">—</span>
          </template>
        </el-table-column>

        <el-table-column label="最后心跳" width="170">
          <template #default="{ row }">
            <el-tooltip
              :content="formatDateTime(row.lastHeartbeatAt)"
              placement="top"
              :disabled="!row.lastHeartbeatAt"
            >
              <span>{{ formatRelativeTime(row.lastHeartbeatAt) }}</span>
            </el-tooltip>
          </template>
        </el-table-column>

        <el-table-column label="启用" width="80">
          <template #default="{ row }">
            <el-tag :type="row.enabled ? 'success' : 'info'" size="small">
              {{ row.enabled ? '是' : '否' }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <el-button
              link
              :type="row.enabled ? 'danger' : 'success'"
              :loading="operating === row.id"
              @click="handleToggle(row)"
            >
              {{ row.enabled ? '停用' : '启用' }}
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

    <!-- 新建节点 -->
    <el-dialog v-model="createDialogVisible" title="新建节点" width="480px">
      <el-form
        ref="createFormRef"
        :model="createForm"
        :rules="createRules"
        label-width="110px"
        @submit.prevent="handleCreate"
      >
        <el-form-item label="节点名称" prop="name">
          <el-input v-model="createForm.name" placeholder="例如 gpu-node-01" />
        </el-form-item>
        <el-form-item label="GPU 型号">
          <el-input v-model="createForm.gpuModel" placeholder="例如 RTX 4090" />
        </el-form-item>
        <el-form-item label="显存 (MB)">
          <el-input-number v-model="createForm.vramMb" :min="0" :step="1024" style="width: 100%" />
        </el-form-item>
        <el-form-item label="显卡档位">
          <el-select v-model="createForm.gpuTier" clearable placeholder="留空则按显存自动推导" style="width: 100%">
            <el-option
              v-for="tier in GPU_TIER_OPTIONS"
              :key="tier.value"
              :label="`${tier.label}（${tier.vramRange}）`"
              :value="tier.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="最长时长 (秒)">
          <el-input-number
            v-model="createForm.maxDurationSeconds"
            :min="0"
            :max="600"
            :step="1"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="支持分辨率">
          <el-input v-model="createForm.supportedResolutions" placeholder="例如 480P,720P,1080P,4K（仅用于展示）" />
        </el-form-item>
        <el-form-item label="Worker 版本">
          <el-input v-model="createForm.workerVersion" placeholder="例如 0.1.0" />
        </el-form-item>
        <el-form-item label="模型版本">
          <el-input v-model="createForm.modelVersion" placeholder="例如 MiniMax-H3" />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="createDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="handleCreate">创建</el-button>
      </template>
    </el-dialog>

    <!-- 一次性 token -->
    <el-dialog
      v-model="tokenDialogVisible"
      title="节点创建成功"
      width="560px"
      :close-on-click-modal="false"
    >
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        title="Token 仅此一次展示，请立即复制并保存到节点。关闭后无法再次查看。"
        style="margin-bottom: 16px"
      />

      <p>
        <span class="text-muted">节点名称：</span>{{ tokenWorker?.name }}
      </p>

      <div class="token-box">
        <code>{{ issuedToken }}</code>
        <el-button type="primary" @click="copyToken">复制</el-button>
      </div>

      <template #footer>
        <el-button type="primary" @click="tokenDialogVisible = false">我已保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>
