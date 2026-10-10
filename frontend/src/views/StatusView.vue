<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getTaskStatus } from '@/api/task'
import { getOrder } from '@/api/order'
import { formatAmount, formatDateTime } from '@/utils/format'
import type { OrderInfo, TaskStatusInfo } from '@/api/types'

const route = useRoute()
const router = useRouter()
const orderNo = route.params.orderNo as string

const task = ref<TaskStatusInfo | null>(null)
const order = ref<OrderInfo | null>(null)
const loading = ref(true)

let pollTimer: ReturnType<typeof setInterval> | null = null

// 是否到达终态（完成 / 失败）——到达后停止轮询
const isTerminal = computed(() => {
  const t = task.value
  if (!t) return false
  return t.printStatus === 2 || t.printStatus === 3
})

/** 步骤条当前进度：0 待支付 / 1 已支付 / 2 打印中 / 3 已完成或失败 */
const stepIndex = computed(() => {
  const t = task.value
  if (!t) return 0
  if (t.payStatus === 0) return 0
  if (t.printStatus === 0) return 1
  if (t.printStatus === 1) return 2
  return 3
})

const stepStatus = computed<'wait' | 'process' | 'finish' | 'error' | 'success'>(() => {
  const t = task.value
  if (!t) return 'wait'
  if (t.printStatus === 2) return 'success'
  if (t.printStatus === 3) return 'error'
  if (t.payStatus === 1) return 'process'
  return 'wait'
})

const statusTag = computed(() => {
  const t = task.value
  if (!t) return { type: 'info', text: '加载中' }
  if (t.printStatus === 2) return { type: 'success', text: '已完成' }
  if (t.printStatus === 3) return { type: 'danger', text: '打印失败' }
  if (t.printStatus === 1) return { type: 'primary', text: '打印中' }
  if (t.payStatus === 1) return { type: 'warning', text: '已支付，等待打印' }
  return { type: 'info', text: '待支付' }
})

async function refresh() {
  try {
    task.value = await getTaskStatus(orderNo)
  } catch {
    // 忽略单次失败
  }
  if (isTerminal.value) stopPolling()
}

function startPolling() {
  pollTimer && clearInterval(pollTimer)
  pollTimer = setInterval(refresh, 3000)
}
function stopPolling() {
  pollTimer && clearInterval(pollTimer)
  pollTimer = null
}

onMounted(async () => {
  // 并行拉取订单详情（展示金额/参数）与任务状态
  getOrder(orderNo)
    .then((o) => (order.value = o))
    .catch(() => {})
  await refresh()
  loading.value = false
  if (!isTerminal.value) startPolling()
})

onUnmounted(stopPolling)
</script>

<template>
  <div class="page-container">
    <el-card shadow="never" v-loading="loading">
      <div class="head">
        <div class="card-title">打印状态</div>
        <el-tag :type="statusTag.type as any" size="large" effect="dark">
          {{ statusTag.text }}
        </el-tag>
      </div>

      <el-steps
        class="mt-24"
        :active="stepIndex"
        :process-status="stepStatus"
        finish-status="success"
        align-center
      >
        <el-step title="待支付" description="订单已创建" />
        <el-step title="已支付" description="等待调度" />
        <el-step title="打印中" description="终端执行中" />
        <el-step
          :title="task?.printStatus === 3 ? '失败' : '已完成'"
          :description="task?.printStatus === 3 ? '请重试或联系管理员' : '请取走文档'"
        />
      </el-steps>

      <el-alert
        v-if="task?.printStatus === 3 && task?.failReason"
        class="mt-24"
        type="error"
        :closable="false"
        :title="`失败原因：${task.failReason}`"
        show-icon
      />

      <el-descriptions class="mt-24" :column="2" border>
        <el-descriptions-item label="订单号">
          <span class="mono">{{ orderNo }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="金额">
          <span class="amount">¥{{ formatAmount(order?.amount) }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="支付状态">
          {{ task?.payStatus === 1 ? '已支付' : '待支付' }}
        </el-descriptions-item>
        <el-descriptions-item label="打印状态">
          {{ task?.printStatusText || statusTag.text }}
        </el-descriptions-item>
        <el-descriptions-item label="创建时间">
          {{ formatDateTime(order?.createdAt) }}
        </el-descriptions-item>
        <el-descriptions-item label="订单失效时间">
          {{ formatDateTime(order?.expireAt) }}
        </el-descriptions-item>
      </el-descriptions>

      <div class="actions mt-24">
        <el-button type="primary" @click="refresh">刷新状态</el-button>
        <el-button @click="router.push('/orders')">我的订单</el-button>
        <el-button v-if="!isTerminal" @click="router.push('/upload')">继续打印</el-button>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.mono {
  font-family: 'Consolas', 'Monaco', monospace;
}
.amount {
  color: #f56c6c;
  font-weight: 600;
}
.actions {
  display: flex;
  gap: 12px;
}
</style>
