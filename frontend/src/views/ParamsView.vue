<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { quote } from '@/api/pricing'
import { createOrder } from '@/api/order'
import { useOrderStore } from '@/stores/order'
import { formatAmount, formatBytes } from '@/utils/format'
import type { ColorMode, DuplexMode, PaperSize, QuoteRequest, QuoteResult } from '@/api/types'

const router = useRouter()
const orderStore = useOrderStore()

const fileInfo = computed(() => orderStore.fileInfo)
const pageCount = computed(() => fileInfo.value?.pageCount || 0)

// 表单绑定 store 中的参数
const form = reactive(orderStore.params)

// 页码范围开关：关闭表示打印全部页
const useRange = ref(false)
const rangeFrom = ref(1)
const rangeTo = ref(1)

const quoting = ref(false)
const quoteResult = ref<QuoteResult | null>(null)
const submitting = ref(false)

let quoteTimer: ReturnType<typeof setTimeout> | null = null

function buildRequest(): QuoteRequest | null {
  if (!orderStore.fileId) return null
  const req: QuoteRequest = {
    fileId: orderStore.fileId,
    copies: form.copies,
    colorMode: form.colorMode as ColorMode,
    duplexMode: form.duplexMode as DuplexMode,
    paperSize: form.paperSize as PaperSize,
    pageRange: null,
  }
  if (useRange.value) {
    req.pageRange = { from: rangeFrom.value, to: rangeTo.value }
  }
  return req
}

async function doQuote() {
  const req = buildRequest()
  if (!req) return
  // 页码范围基础校验
  if (useRange.value) {
    if (rangeFrom.value < 1 || rangeTo.value < rangeFrom.value || rangeTo.value > pageCount.value) {
      ElMessage.warning(`页码范围需在 1 ~ ${pageCount.value} 之间`)
      quoteResult.value = null
      return
    }
  }
  quoting.value = true
  try {
    quoteResult.value = await quote(req)
  } catch {
    quoteResult.value = null
  } finally {
    quoting.value = false
  }
}

/** 参数变化后 300ms 防抖试算 */
function scheduleQuote() {
  quoteTimer && clearTimeout(quoteTimer)
  quoteTimer = setTimeout(doQuote, 300)
}

watch([() => form.copies, () => form.colorMode, () => form.duplexMode, () => form.paperSize], scheduleQuote)
watch([useRange, rangeFrom, rangeTo], scheduleQuote)

async function handleSubmit() {
  const req = buildRequest()
  if (!req) {
    ElMessage.warning('请先上传文件')
    return
  }
  submitting.value = true
  try {
    const order = await createOrder(req)
    orderStore.orderNo = order.orderNo
    orderStore.params = { ...form, pageRange: req.pageRange ?? null }
    ElMessage.success(`下单成功，订单号 ${order.orderNo}`)
    router.push(`/pay/${order.orderNo}`)
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  if (!orderStore.fileId) {
    ElMessage.warning('请先上传文件')
    router.replace('/upload')
    return
  }
  rangeTo.value = pageCount.value || 1
  doQuote()
})
</script>

<template>
  <div class="page-container">
    <el-card v-if="fileInfo" shadow="never" class="file-card">
      <div class="file-line">
        <span class="file-name">📄 {{ fileInfo.originalName }}</span>
        <el-tag size="small" type="info">{{ formatBytes(fileInfo.sizeBytes) }}</el-tag>
        <el-tag size="small" type="success">{{ fileInfo.pageCount }} 页</el-tag>
      </div>
    </el-card>

    <el-card shadow="never" class="mt-16">
      <div class="card-title">打印参数</div>

      <el-form label-width="110px" label-position="right">
        <el-form-item label="份数">
          <el-input-number v-model="form.copies" :min="1" :max="99" />
        </el-form-item>

        <el-form-item label="色彩">
          <el-radio-group v-model="form.colorMode">
            <el-radio-button value="MONO">黑白</el-radio-button>
            <el-radio-button value="COLOR">彩色</el-radio-button>
          </el-radio-group>
        </el-form-item>

        <el-form-item label="单/双面">
          <el-radio-group v-model="form.duplexMode">
            <el-radio-button value="SIMPLEX">单面</el-radio-button>
            <el-radio-button value="DUPLEX">双面</el-radio-button>
          </el-radio-group>
        </el-form-item>

        <el-form-item label="纸张">
          <el-radio-group v-model="form.paperSize">
            <el-radio-button value="A4">A4</el-radio-button>
            <el-radio-button value="A3">A3</el-radio-button>
          </el-radio-group>
        </el-form-item>

        <el-form-item label="页码范围">
          <el-switch v-model="useRange" active-text="指定页码" inactive-text="全部页" />
          <div v-if="useRange" class="range-row">
            <el-input-number v-model="rangeFrom" :min="1" :max="pageCount" controls-position="right" />
            <span class="range-sep">至</span>
            <el-input-number v-model="rangeTo" :min="1" :max="pageCount" controls-position="right" />
            <span class="text-muted range-hint">（文件共 {{ pageCount }} 页）</span>
          </div>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never" class="mt-16 quote-card" v-loading="quoting">
      <div class="card-title">费用试算</div>
      <div v-if="quoteResult" class="quote-grid">
        <div class="quote-item">
          <div class="quote-label">计费页数</div>
          <div class="quote-value">{{ quoteResult.billablePages }}</div>
        </div>
        <div class="quote-item">
          <div class="quote-label">单价</div>
          <div class="quote-value">¥{{ formatAmount(quoteResult.unitPrice) }}</div>
        </div>
        <div class="quote-item">
          <div class="quote-label">双面折扣</div>
          <div class="quote-value">×{{ Number(quoteResult.duplexDiscount).toFixed(2) }}</div>
        </div>
        <div class="quote-item total">
          <div class="quote-label">应付金额</div>
          <div class="quote-value">¥{{ formatAmount(quoteResult.amount) }}</div>
        </div>
      </div>
      <el-empty v-else :image-size="60" description="暂无试算结果" />

      <div class="actions mt-16">
        <el-button
          type="primary"
          size="large"
          :loading="submitting"
          :disabled="!quoteResult"
          @click="handleSubmit"
        >
          提交下单
        </el-button>
        <el-button size="large" @click="router.push('/upload')">返回修改文件</el-button>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.file-card {
  border-left: 4px solid var(--sp-primary);
}
.file-line {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.file-name {
  font-weight: 600;
}
.range-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 10px;
}
.range-sep {
  color: #909399;
}
.range-hint {
  font-size: 12px;
}
.quote-card {
  border: 1px solid #e4e7ed;
}
.quote-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
}
.quote-item {
  background: #f7f9fc;
  border-radius: 10px;
  padding: 16px;
  text-align: center;
}
.quote-item.total {
  background: #ecf5ff;
}
.quote-label {
  font-size: 13px;
  color: #909399;
  margin-bottom: 8px;
}
.quote-value {
  font-size: 20px;
  font-weight: 700;
  color: #303133;
}
.quote-item.total .quote-value {
  color: #f56c6c;
}
.actions {
  display: flex;
  gap: 12px;
}
</style>
