<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import QRCode from 'qrcode'
import { createPay, getPayStatus, mockConfirm } from '@/api/payment'
import { formatAmount } from '@/utils/format'
import type { PayInfo } from '@/api/types'

const route = useRoute()
const router = useRouter()
const orderNo = route.params.orderNo as string

const payInfo = ref<PayInfo | null>(null)
const qrDataUrl = ref('')
const loading = ref(true)
const confirming = ref(false)
const remainSeconds = ref(0)

let pollTimer: ReturnType<typeof setInterval> | null = null
let countTimer: ReturnType<typeof setInterval> | null = null

const expired = computed(() => remainSeconds.value <= 0)

async function loadPay() {
  loading.value = true
  try {
    const info = await createPay(orderNo)
    payInfo.value = info
    // 用 qrcode 库把 qrCodeContent 渲染为二维码
    qrDataUrl.value = await QRCode.toDataURL(info.qrCodeContent, {
      width: 260,
      margin: 1,
      color: { dark: '#1f2d3d', light: '#ffffff' },
    })
    // 支付有效期倒计时
    const expireTs = new Date(info.expireAt).getTime()
    remainSeconds.value = Math.max(0, Math.floor((expireTs - Date.now()) / 1000))
    startCountdown()
  } finally {
    loading.value = false
  }
}

function startCountdown() {
  countTimer && clearInterval(countTimer)
  countTimer = setInterval(() => {
    if (remainSeconds.value > 0) remainSeconds.value -= 1
  }, 1000)
}

/** 轮询支付状态，成功后跳转打印状态页 */
function startPolling() {
  pollTimer && clearInterval(pollTimer)
  pollTimer = setInterval(async () => {
    try {
      const st = await getPayStatus(orderNo)
      if (st.payStatus === 1) {
        stopTimers()
        ElMessage.success('支付成功，正在生成打印任务')
        router.replace(`/status/${orderNo}`)
      }
    } catch {
      // 轮询失败忽略，等待下次
    }
  }, 3000)
}

function stopTimers() {
  pollTimer && clearInterval(pollTimer)
  countTimer && clearInterval(countTimer)
  pollTimer = null
  countTimer = null
}

/** demo 档：模拟支付成功 */
async function handleMockPay() {
  confirming.value = true
  try {
    await mockConfirm(orderNo, 'SUCCESS')
    ElMessage.success('模拟支付成功')
    stopTimers()
    router.replace(`/status/${orderNo}`)
  } finally {
    confirming.value = false
  }
}

onMounted(async () => {
  await loadPay()
  startPolling()
})

onUnmounted(stopTimers)
</script>

<template>
  <div class="page-container pay-page">
    <el-card shadow="never" class="pay-card" v-loading="loading">
      <div class="card-title">扫码支付</div>

      <div v-if="payInfo" class="pay-body">
        <div class="qr-box">
          <img v-if="qrDataUrl" :src="qrDataUrl" alt="收款二维码" class="qr-img" />
          <el-skeleton v-else :rows="4" animated />
          <p class="qr-hint">请使用手机扫码完成支付</p>
        </div>

        <div class="pay-meta">
          <div class="meta-row">
            <span class="meta-label">订单号</span>
            <span class="meta-value mono">{{ payInfo.orderNo }}</span>
          </div>
          <div class="meta-row">
            <span class="meta-label">应付金额</span>
            <span class="meta-value amount">¥{{ formatAmount(payInfo.amount) }}</span>
          </div>
          <div class="meta-row">
            <span class="meta-label">支付渠道</span>
            <span class="meta-value">{{ payInfo.gateway }}</span>
          </div>
          <div class="meta-row">
            <span class="meta-label">剩余时间</span>
            <span class="meta-value" :class="{ danger: expired }">
              {{ expired ? '已过期，请重新下单' : `${remainSeconds}s` }}
            </span>
          </div>
        </div>
      </div>

      <el-alert
        class="mt-16"
        type="info"
        :closable="false"
        title="演示环境：点击下方按钮模拟支付成功回调，也可等待自动轮询"
        show-icon
      />

      <div class="actions mt-16">
        <el-button type="success" size="large" :loading="confirming" @click="handleMockPay">
          模拟支付成功
        </el-button>
        <el-button size="large" @click="router.push('/orders')">返回订单列表</el-button>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.pay-page {
  max-width: 820px;
}
.pay-card {
  border-radius: var(--sp-card-radius);
}
.pay-body {
  display: flex;
  gap: 32px;
  align-items: center;
  flex-wrap: wrap;
}
.qr-box {
  text-align: center;
}
.qr-img {
  width: 260px;
  height: 260px;
  border: 1px solid #ebeef5;
  border-radius: 10px;
  padding: 8px;
  background: #fff;
}
.qr-hint {
  font-size: 13px;
  color: #909399;
  margin: 12px 0 0;
}
.pay-meta {
  flex: 1;
  min-width: 240px;
}
.meta-row {
  display: flex;
  justify-content: space-between;
  padding: 12px 0;
  border-bottom: 1px dashed #ebeef5;
  font-size: 14px;
}
.meta-row:last-child {
  border-bottom: none;
}
.meta-label {
  color: #909399;
}
.meta-value {
  color: #303133;
  font-weight: 600;
}
.mono {
  font-family: 'Consolas', 'Monaco', monospace;
}
.amount {
  color: #f56c6c;
  font-size: 18px;
}
.danger {
  color: #f56c6c;
}
.actions {
  display: flex;
  gap: 12px;
}
</style>
