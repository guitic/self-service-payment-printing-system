<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, type FormInstance } from 'element-plus'
import {
  adminListOrders,
  createPriceRule,
  createTerminal,
  listPriceRules,
  listTerminals,
  updatePriceRule,
} from '@/api/admin'
import { formatAmount, formatDateTime } from '@/utils/format'
import {
  PAY_STATUS_TEXT,
  PRINT_STATUS_TEXT,
  type OrderInfo,
  type PaperSize,
  type PriceRule,
  type Terminal,
} from '@/api/types'

const activeTab = ref('rules')

/* ---------------- 计价规则 ---------------- */
const rules = ref<PriceRule[]>([])
const rulesLoading = ref(false)
const ruleDialog = ref(false)
const ruleSaving = ref(false)
const ruleFormRef = ref<FormInstance>()
const editingRuleId = ref<number | null>(null)

const ruleForm = reactive<PriceRule>({
  name: '',
  paperSize: 'A4' as PaperSize,
  colorPrice: 0.8,
  monoPrice: 0.2,
  duplexDiscount: 0.9,
  minCharge: 1,
})

async function fetchRules() {
  rulesLoading.value = true
  try {
    rules.value = await listPriceRules()
  } finally {
    rulesLoading.value = false
  }
}

function openRuleDialog(row?: PriceRule) {
  if (row) {
    editingRuleId.value = row.id ?? null
    Object.assign(ruleForm, row)
  } else {
    editingRuleId.value = null
    Object.assign(ruleForm, {
      name: '',
      paperSize: 'A4',
      colorPrice: 0.8,
      monoPrice: 0.2,
      duplexDiscount: 0.9,
      minCharge: 1,
    })
  }
  ruleDialog.value = true
}

async function saveRule() {
  if (!ruleForm.name) {
    ElMessage.warning('请填写规则名称')
    return
  }
  ruleSaving.value = true
  try {
    if (editingRuleId.value != null) {
      await updatePriceRule(editingRuleId.value, { ...ruleForm })
      ElMessage.success('规则已更新')
    } else {
      await createPriceRule({ ...ruleForm })
      ElMessage.success('规则已创建')
    }
    ruleDialog.value = false
    fetchRules()
  } finally {
    ruleSaving.value = false
  }
}

/* ---------------- 订单 ---------------- */
const orders = ref<OrderInfo[]>([])
const orderTotal = ref(0)
const orderPage = ref(1)
const orderSize = ref(10)
const ordersLoading = ref(false)

async function fetchOrders() {
  ordersLoading.value = true
  try {
    const res = await adminListOrders(orderPage.value, orderSize.value)
    orders.value = res.list || []
    orderTotal.value = res.total || 0
  } finally {
    ordersLoading.value = false
  }
}

function handleOrderPage(p: number) {
  orderPage.value = p
  fetchOrders()
}

/* ---------------- 终端 ---------------- */
const terminals = ref<Terminal[]>([])
const terminalsLoading = ref(false)
const terminalDialog = ref(false)
const terminalSaving = ref(false)
const terminalForm = reactive({ code: '', name: '', agentToken: '' })

async function fetchTerminals() {
  terminalsLoading.value = true
  try {
    terminals.value = await listTerminals()
  } finally {
    terminalsLoading.value = false
  }
}

function openTerminalDialog() {
  terminalForm.code = ''
  terminalForm.name = ''
  terminalForm.agentToken = ''
  terminalDialog.value = true
}

async function saveTerminal() {
  if (!terminalForm.code || !terminalForm.name || !terminalForm.agentToken) {
    ElMessage.warning('请完整填写终端信息')
    return
  }
  terminalSaving.value = true
  try {
    await createTerminal({ ...terminalForm })
    ElMessage.success('终端已创建')
    terminalDialog.value = false
    fetchTerminals()
  } finally {
    terminalSaving.value = false
  }
}

onMounted(() => {
  fetchRules()
  fetchOrders()
  fetchTerminals()
})
</script>

<template>
  <div class="page-container">
    <el-card shadow="never">
      <div class="card-title">管理端</div>

      <el-tabs v-model="activeTab">
        <!-- 计价规则 -->
        <el-tab-pane label="计价规则" name="rules">
          <div class="toolbar">
            <el-button type="primary" @click="openRuleDialog()">新增规则</el-button>
            <el-button @click="fetchRules">刷新</el-button>
          </div>
          <el-table v-loading="rulesLoading" :data="rules" stripe>
            <el-table-column prop="id" label="ID" width="70" />
            <el-table-column prop="name" label="名称" min-width="140" />
            <el-table-column prop="paperSize" label="纸张" width="80" />
            <el-table-column label="彩色单价" width="110">
              <template #default="{ row }">¥{{ formatAmount(row.colorPrice) }}</template>
            </el-table-column>
            <el-table-column label="黑白单价" width="110">
              <template #default="{ row }">¥{{ formatAmount(row.monoPrice) }}</template>
            </el-table-column>
            <el-table-column label="双面折扣" width="110">
              <template #default="{ row }">×{{ Number(row.duplexDiscount).toFixed(2) }}</template>
            </el-table-column>
            <el-table-column label="起印价" width="110">
              <template #default="{ row }">¥{{ formatAmount(row.minCharge) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="90" fixed="right">
              <template #default="{ row }">
                <el-button type="primary" link @click="openRuleDialog(row)">编辑</el-button>
              </template>
            </el-table-column>
            <template #empty><el-empty description="暂无规则" /></template>
          </el-table>
        </el-tab-pane>

        <!-- 订单列表 -->
        <el-tab-pane label="订单列表" name="orders">
          <div class="toolbar">
            <el-button @click="fetchOrders">刷新</el-button>
          </div>
          <el-table v-loading="ordersLoading" :data="orders" stripe>
            <el-table-column prop="orderNo" label="订单号" min-width="180">
              <template #default="{ row }"><span class="mono">{{ row.orderNo }}</span></template>
            </el-table-column>
            <el-table-column label="金额" width="110">
              <template #default="{ row }">¥{{ formatAmount(row.amount) }}</template>
            </el-table-column>
            <el-table-column label="支付" width="110">
              <template #default="{ row }">
                {{ row.payStatusText || PAY_STATUS_TEXT[row.payStatus] || '-' }}
              </template>
            </el-table-column>
            <el-table-column label="打印" width="110">
              <template #default="{ row }">
                {{ row.printStatusText || PRINT_STATUS_TEXT[row.printStatus] || '-' }}
              </template>
            </el-table-column>
            <el-table-column label="创建时间" min-width="170">
              <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
            </el-table-column>
            <template #empty><el-empty description="暂无订单" /></template>
          </el-table>
          <div class="pager">
            <el-pagination
              background
              layout="total, prev, pager, next"
              :total="orderTotal"
              :current-page="orderPage"
              :page-size="orderSize"
              @current-change="handleOrderPage"
            />
          </div>
        </el-tab-pane>

        <!-- 终端列表 -->
        <el-tab-pane label="终端列表" name="terminals">
          <div class="toolbar">
            <el-button type="primary" @click="openTerminalDialog">新增终端</el-button>
            <el-button @click="fetchTerminals">刷新</el-button>
          </div>
          <el-table v-loading="terminalsLoading" :data="terminals" stripe>
            <el-table-column prop="code" label="终端编码" width="140" />
            <el-table-column prop="name" label="名称" min-width="140" />
            <el-table-column label="在线状态" width="120">
              <template #default="{ row }">
                <el-tag :type="row.online ? 'success' : 'info'" effect="light">
                  {{ row.online ? '在线' : '离线' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="最后心跳" min-width="170">
              <template #default="{ row }">{{ formatDateTime(row.lastHeartbeatAt) }}</template>
            </el-table-column>
            <template #empty><el-empty description="暂无终端" /></template>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <!-- 规则编辑弹窗 -->
    <el-dialog
      v-model="ruleDialog"
      :title="editingRuleId != null ? '编辑计价规则' : '新增计价规则'"
      width="480px"
    >
      <el-form ref="ruleFormRef" :model="ruleForm" label-width="90px">
        <el-form-item label="名称">
          <el-input v-model="ruleForm.name" placeholder="如：A4 标准" />
        </el-form-item>
        <el-form-item label="纸张">
          <el-radio-group v-model="ruleForm.paperSize">
            <el-radio-button value="A4">A4</el-radio-button>
            <el-radio-button value="A3">A3</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="彩色单价">
          <el-input-number v-model="ruleForm.colorPrice" :min="0" :step="0.1" :precision="2" />
        </el-form-item>
        <el-form-item label="黑白单价">
          <el-input-number v-model="ruleForm.monoPrice" :min="0" :step="0.1" :precision="2" />
        </el-form-item>
        <el-form-item label="双面折扣">
          <el-input-number v-model="ruleForm.duplexDiscount" :min="0" :max="1" :step="0.05" :precision="2" />
        </el-form-item>
        <el-form-item label="起印价">
          <el-input-number v-model="ruleForm.minCharge" :min="0" :step="0.5" :precision="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="ruleDialog = false">取消</el-button>
        <el-button type="primary" :loading="ruleSaving" @click="saveRule">保存</el-button>
      </template>
    </el-dialog>

    <!-- 终端新建弹窗 -->
    <el-dialog v-model="terminalDialog" title="新增终端" width="440px">
      <el-form :model="terminalForm" label-width="90px">
        <el-form-item label="终端编码">
          <el-input v-model="terminalForm.code" placeholder="如：TERM-002" />
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="terminalForm.name" placeholder="如：一楼大厅终端" />
        </el-form-item>
        <el-form-item label="助手令牌">
          <el-input v-model="terminalForm.agentToken" placeholder="X-Agent-Token" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="terminalDialog = false">取消</el-button>
        <el-button type="primary" :loading="terminalSaving" @click="saveTerminal">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  gap: 10px;
  margin-bottom: 14px;
}
.mono {
  font-family: 'Consolas', 'Monaco', monospace;
}
.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
</style>
