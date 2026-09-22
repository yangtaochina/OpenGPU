<script setup lang="ts">
import { computed } from 'vue'
import { ElTag } from 'element-plus'

import type { AttemptStatus, TaskStatus, WorkerRuntimeStatus, WorkerStatus } from '@/types/api'
import {
  attemptStatusMeta,
  taskStatusMeta,
  workerRuntimeStatusMeta,
  workerStatusMeta,
  type StatusMeta,
} from '@/utils/status'

export type StatusTagKind = 'task' | 'worker' | 'runtime' | 'attempt'

const props = withDefaults(
  defineProps<{
    status: TaskStatus | WorkerStatus | WorkerRuntimeStatus | AttemptStatus | string
    kind?: StatusTagKind
    size?: 'small' | 'default' | 'large'
  }>(),
  {
    kind: 'task',
    size: 'small',
  },
)

const meta = computed<StatusMeta>(() => {
  switch (props.kind) {
    case 'worker':
      return workerStatusMeta(props.status)
    case 'runtime':
      return workerRuntimeStatusMeta(props.status)
    case 'attempt':
      return attemptStatusMeta(props.status)
    case 'task':
    default:
      return taskStatusMeta(props.status)
  }
})
</script>

<template>
  <ElTag :type="meta.tagType" :size="size" effect="light">{{ meta.label }}</ElTag>
</template>
