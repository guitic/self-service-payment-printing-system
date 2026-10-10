<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { cancelOrder, listOrders } from '@/api/order'
import { formatAmount, formatDateTime } from '@/utils/format'
import { PAY_STATUS_TEXT, PRINT_STATUS_TEXT, type OrderInfo } from '@/api/types'

const router = useRouter()

const list = ref<OrderInfo[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const loading = ref(false)

function payTagType(payStatus: number): 'success' | 'warning' | 'info' | 'danger' {
  if (payStatus === 1) return 'success'
  if (payStatus === 0) return 'warning'
  if (payStatus === 3) return 'danger'
  return 'info'
}

function printTagType(printStatus: number): 'success' | 'warning' | 'info' | 'danger' {
  if (printStatus === 2) return 'success'
  if (printStatus === 1) return 'warning'
  if (printStatus === 3) return 'danger'
  return 'info'
}

async function fetchList() {
  loading.value = true
  try {
    const res = await listOrders(page.value, size.value)
    list.value = res.list || []
    total.value = res.total || 0
  } finally {
    loading.value = false
  }
}

function handlePageChange(p: number) {
  page.value = p
  fetchList()
}

async function handleCancel(orderNo: string) {
  await ElMessageBox.confirm(`确定取消订单 ${orderNo} 吗？`, '取消订单', { type: 'warning' })
  await cancelOrder(orderNo)
  ElMessage.success('订单已取消')
  fetchList()
}

function goPay(orderNo: string) {
  router.push(`/pay/${orderNo}`)
}
function goStatus(orderNo: string) {
  router.push(`/status/${orderNo}`)
}

onMounted(fetchList)
</script>

<template>
  <div class="page-container">
    <el-card shadow="never">
      <div class="head">
        <div class="card-title">我的订单</div>
        <el-button type="primary" plain @click="router.push('/upload')">新建打印</el-button>
      </div>

      <el-table v-loading="loading" :data="list" stripe style="width: 100%">
        <el-table-column prop="orderNo" label="订单号" min-width="180">
          <template #default="{ row }">
            <span class="mono">{{ row.orderNo }}</span>
          </template>
        </el-table-column>
        <el-table-column label="金额" width="110">
          <template #default="{ row }">
            <span class="amount">¥{{ formatAmount(row.amount) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="支付状态" width="120">
          <template #default="{ row }">
            <el-tag :type="payTagType(row.payStatus)" effect="light">
              {{ row.payStatusText || PAY_STATUS_TEXT[row.payStatus] || '-' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="打印状态" width="120">
          <template #default="{ row }">
            <el-tag :type="printTagType(row.printStatus)" effect="light">
              {{ row.printStatusText || PRINT_STATUS_TEXT[row.printStatus] || '-' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" min-width="170">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.payStatus === 0"
              type="primary"
              link
              @click="goPay(row.orderNo)"
            >
              去支付
            </el-button>
            <el-button type="primary" link @click="goStatus(row.orderNo)">查看状态</el-button>
            <el-button
              v-if="row.payStatus === 0"
              type="danger"
              link
              @click="handleCancel(row.orderNo)"
            >
              取消
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无订单" />
        </template>
      </el-table>

      <div class="pager">
        <el-pagination
          background
          layout="total, prev, pager, next"
          :total="total"
          :current-page="page"
          :page-size="size"
          @current-change="handlePageChange"
        />
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
.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
</style>
