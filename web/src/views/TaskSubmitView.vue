<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'

import StatusTag from '@/components/StatusTag.vue'
import { cancelTask, createTask, getCapabilities, listTasks } from '@/api/tasks'
import type {
  CapabilityMatrixEntry,
  CapabilityResolutionOption,
  CapabilityView,
  TaskRequirementView,
  TaskView,
  VideoRequirementInput,
  VideoResolution,
} from '@/types/api'
import { formatDateTime, shortId, summarizePrompt } from '@/utils/format'
import { isTerminalTaskStatus } from '@/utils/status'

const router = useRouter()

const POLL_INTERVAL = 3000
const PROMPT_MAX_LENGTH = 2000

// ---------- 提交任务 ----------

const DEFAULT_REQUIREMENT: VideoRequirementInput = {
  resolution: '1080P',
  durationSeconds: 5,
  fps: 30,
}

// capabilities 加载失败时的兜底选项（仅枚举展示，推导结果仍以服务端 matrix 为准）
const FALLBACK_RESOLUTIONS: CapabilityResolutionOption[] = [
  { value: '480P', label: '480P', width: 854, height: 480 },
  { value: '720P', label: '720P', width: 1280, height: 720 },
  { value: '1080P', label: '1080P', width: 1920, height: 1080 },
  { value: '4K', label: '4K', width: 3840, height: 2160 },
]
const FALLBACK_DURATIONS = [3, 5, 10, 15]
const FALLBACK_FPS = [24, 30, 60]

const formRef = ref<FormInstance>()
const submitting = ref(false)
const form = reactive({
  prompt: '',
  maxRetry: undefined as number | undefined,
  resolution: DEFAULT_REQUIREMENT.resolution,
  durationSeconds: DEFAULT_REQUIREMENT.durationSeconds,
  fps: DEFAULT_REQUIREMENT.fps,
})

const rules: FormRules<typeof form> = {
  prompt: [
    { required: true, message: '请输入提示词', trigger: 'blur' },
    {
      validator: (_rule, value: string, callback) => {
        const trimmed = (value ?? '').trim()
        if (!trimmed) {
          callback(new Error('提示词不能为空'))
        } else if (trimmed.length > PROMPT_MAX_LENGTH) {
          callback(new Error(`提示词长度不能超过 ${PROMPT_MAX_LENGTH} 个字符`))
        } else {
          callback()
        }
      },
      trigger: 'blur',
    },
  ],
}

// ---------- 视频要求与能力矩阵 ----------

const capabilities = ref<CapabilityView | null>(null)

const resolutionOptions = computed(
  () => capabilities.value?.options.resolutions ?? FALLBACK_RESOLUTIONS,
)
const durationOptions = computed(() => capabilities.value?.options.durations ?? FALLBACK_DURATIONS)
const fpsOptions = computed(() => capabilities.value?.options.fps ?? FALLBACK_FPS)

async function loadCapabilities(): Promise<void> {
  try {
    capabilities.value = await getCapabilities()
  } catch {
    // 错误提示已由拦截器处理；选项回退到本地枚举
  }
}

/** 查 capabilities.matrix，不在前端重复实现推导公式 */
function findMatrixEntry(
  resolution: VideoResolution,
  durationSeconds: number,
  fps: number,
): CapabilityMatrixEntry | undefined {
  return capabilities.value?.matrix.find(
    (entry) =>
      entry.resolution === resolution &&
      entry.durationSeconds === durationSeconds &&
      entry.fps === fps,
  )
}

const selectedMatrixEntry = computed(() =>
  findMatrixEntry(form.resolution, form.durationSeconds, form.fps),
)

function resetRequirement(): void {
  form.resolution = DEFAULT_REQUIREMENT.resolution
  form.durationSeconds = DEFAULT_REQUIREMENT.durationSeconds
  form.fps = DEFAULT_REQUIREMENT.fps
}

async function handleSubmit(): Promise<void> {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  submitting.value = true
  try {
    const created = await createTask({
      prompt: form.prompt.trim(),
      maxRetry: form.maxRetry,
      requirement: {
        resolution: form.resolution,
        durationSeconds: form.durationSeconds,
        fps: form.fps,
      },
    })
    ElMessage.success(`任务已提交，任务号 ${shortId(created.id)}`)
    form.prompt = ''
    form.maxRetry = undefined
    formRef.value.resetFields()
    resetRequirement()
    query.page = 1
    await loadTasks()
  } catch {
    // 错误提示已由响应拦截器统一处理
  } finally {
    submitting.value = false
  }
}

function handleReset(): void {
  formRef.value?.resetFields()
  form.prompt = ''
  form.maxRetry = undefined
  resetRequirement()
}

// ---------- 我的任务列表 ----------

const loading = ref(false)
const tasks = ref<TaskView[]>([])
const total = ref(0)
const query = reactive({ page: 1, size: 10 })

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
    const page = await listTasks({ page: query.page - 1, size: query.size })
    tasks.value = page.content ?? []
    total.value = page.totalElements ?? 0
    ensurePolling()
  } catch {
    // 保持上次数据；错误提示已由拦截器处理
  } finally {
    if (!silent) loading.value = false
  }
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

async function handleCancel(task: TaskView): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确认取消任务 ${shortId(task.id)} 吗？仅排队中的任务可取消。`,
      '取消任务',
      { type: 'warning', confirmButtonText: '确认取消', cancelButtonText: '返回' },
    )
  } catch {
    return
  }
  try {
    await cancelTask(task.id)
    ElMessage.success('任务已取消')
    await loadTasks()
  } catch {
    // 40900 等错误已由拦截器提示
  }
}

function progressStatus(task: TaskView): 'success' | 'exception' | 'warning' | undefined {
  if (task.status === 'FAILED') return 'exception'
  if (task.status === 'SUCCEEDED') return 'success'
  if (task.status === 'CANCELED') return 'warning'
  return undefined
}

/** 任务列表「视频要求」精简展示 */
function formatRequirementShort(requirement: TaskRequirementView | null): string {
  if (!requirement) return '—'
  return `${requirement.resolution} · ${requirement.durationSeconds}s · ${requirement.fps}fps`
}

/** QUEUED 且当前 fleet 下不可服务 -> 等待节点 */
function isWaitingForNode(task: TaskView): boolean {
  if (task.status !== 'QUEUED' || !task.requirement) return false
  const entry = findMatrixEntry(
    task.requirement.resolution,
    task.requirement.durationSeconds,
    task.requirement.fps,
  )
  return entry?.serviceable === false
}

onMounted(() => {
  void loadTasks()
  void loadCapabilities()
})

onUnmounted(() => {
  stopPolling()
})
</script>

<template>
  <div class="page-container">
    <div class="page-header">
      <h2>提交任务</h2>
    </div>

    <el-card class="card" shadow="never">
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        @submit.prevent="handleSubmit"
      >
        <el-form-item label="提示词" prop="prompt">
          <el-input
            v-model="form.prompt"
            type="textarea"
            :rows="4"
            :maxlength="PROMPT_MAX_LENGTH"
            show-word-limit
            placeholder="例如：一只在星空下奔跑的机械鹿"
          />
        </el-form-item>

        <el-form-item label="最大重试次数（可选，0–5）">
          <el-input-number v-model="form.maxRetry" :min="0" :max="5" :step="1" />
          <span class="form-tip" style="margin-left: 12px">留空则使用服务端默认配置</span>
        </el-form-item>

        <el-form-item label="视频要求（分辨率 / 时长 / 帧率）">
          <div class="requirement-row">
            <el-select v-model="form.resolution" placeholder="分辨率" style="width: 150px">
              <el-option
                v-for="option in resolutionOptions"
                :key="option.value"
                :label="option.label"
                :value="option.value"
              />
            </el-select>
            <el-select v-model="form.durationSeconds" placeholder="时长" style="width: 150px">
              <el-option
                v-for="duration in durationOptions"
                :key="duration"
                :label="`${duration} 秒`"
                :value="duration"
              />
            </el-select>
            <el-select v-model="form.fps" placeholder="帧率" style="width: 150px">
              <el-option
                v-for="value in fpsOptions"
                :key="value"
                :label="`${value} fps`"
                :value="value"
              />
            </el-select>
          </div>
          <div v-if="selectedMatrixEntry" class="requirement-summary">
            {{ selectedMatrixEntry.summary }}
          </div>
          <el-alert
            v-if="selectedMatrixEntry && !selectedMatrixEntry.serviceable"
            style="margin-top: 8px"
            type="warning"
            :closable="false"
            show-icon
            title="当前没有满足该要求的在线节点，任务会排队等待；节点上线后会自动被领取。"
          />
        </el-form-item>

        <el-form-item>
          <el-button type="primary" :loading="submitting" @click="handleSubmit">
            提交任务
          </el-button>
          <el-button @click="handleReset">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card id="my-tasks" class="card" shadow="never">
      <template #header>
        <div class="page-header" style="margin-bottom: 0">
          <span style="font-weight: 600">我的任务</span>
          <el-button size="small" :loading="loading" @click="loadTasks()">刷新</el-button>
        </div>
      </template>

      <el-table v-loading="loading" :data="tasks" stripe empty-text="暂无任务">
        <el-table-column label="任务号" width="120">
          <template #default="{ row }">
            <span class="mono">{{ shortId(row.id) }}</span>
          </template>
        </el-table-column>

        <el-table-column label="提示词摘要" min-width="220">
          <template #default="{ row }">
            <el-tooltip :content="row.prompt" placement="top" :disabled="!row.prompt">
              <span class="text-ellipsis">{{ summarizePrompt(row.prompt) }}</span>
            </el-tooltip>
          </template>
        </el-table-column>

        <el-table-column label="视频要求" min-width="150">
          <template #default="{ row }">
            <span :class="{ 'text-muted': !row.requirement }">
              {{ formatRequirementShort(row.requirement) }}
            </span>
          </template>
        </el-table-column>

        <el-table-column label="状态" width="150">
          <template #default="{ row }">
            <div class="status-cell">
              <StatusTag :status="row.status" kind="task" />
              <el-tag v-if="isWaitingForNode(row)" type="info" size="small">等待节点</el-tag>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="进度" width="180">
          <template #default="{ row }">
            <el-progress
              :percentage="row.progress ?? 0"
              :status="progressStatus(row)"
              :stroke-width="12"
            />
          </template>
        </el-table-column>

        <el-table-column label="创建时间" width="180">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>

        <el-table-column label="操作" width="140" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="goDetail(row.id)">查看</el-button>
            <el-button
              link
              type="danger"
              :disabled="row.status !== 'QUEUED'"
              @click="handleCancel(row)"
            >
              取消
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

<style scoped>
.requirement-row {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}

.requirement-summary {
  margin-top: 8px;
  color: var(--el-text-color-regular);
}

.status-cell {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px;
}
</style>
