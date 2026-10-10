<script setup lang="ts">
import { computed, onUnmounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { sendSms } from '@/api/auth'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

const formRef = ref<FormInstance>()
const form = reactive({ phone: '', code: '' })
const loading = ref(false)

// 验证码倒计时（同号 60s 冷却）
const countdown = ref(0)
const sending = ref(false)
let timer: ReturnType<typeof setInterval> | null = null

const rules: FormRules = {
  phone: [
    { required: true, message: '请输入手机号', trigger: 'blur' },
    { pattern: /^1[3-9]\d{9}$/, message: '手机号格式不正确', trigger: 'blur' },
  ],
  code: [
    { required: true, message: '请输入验证码', trigger: 'blur' },
    { pattern: /^\d{4,6}$/, message: '验证码为 4-6 位数字', trigger: 'blur' },
  ],
}

const canSend = computed(() => countdown.value === 0 && !sending.value)

function startCountdown(seconds: number) {
  countdown.value = seconds
  timer && clearInterval(timer)
  timer = setInterval(() => {
    countdown.value -= 1
    if (countdown.value <= 0) {
      timer && clearInterval(timer)
      timer = null
    }
  }, 1000)
}

async function handleSend() {
  if (!/^1[3-9]\d{9}$/.test(form.phone)) {
    ElMessage.warning('请先输入正确的手机号')
    return
  }
  sending.value = true
  try {
    const res = await sendSms(form.phone)
    startCountdown(60)
    // demo 档返回 debugCode：自动回填并提示
    if (res.debugCode) {
      form.code = res.debugCode
      ElMessage.success(`演示环境验证码：${res.debugCode}（已自动填入）`)
    } else {
      ElMessage.success(`验证码已发送，${res.expireSeconds}s 内有效`)
    }
  } finally {
    sending.value = false
  }
}

async function handleLogin() {
  if (!formRef.value) return
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    loading.value = true
    try {
      await userStore.login(form.phone, form.code)
      ElMessage.success('登录成功')
      const redirect = (route.query.redirect as string) || '/upload'
      router.push(redirect)
    } finally {
      loading.value = false
    }
  })
}

onUnmounted(() => {
  timer && clearInterval(timer)
})
</script>

<template>
  <div class="login-page">
    <div class="login-card">
      <div class="login-head">
        <div class="login-logo">🖨️</div>
        <h1 class="login-title">自助付费打印系统</h1>
        <p class="login-sub">上传文档 · 扫码支付 · 自助取件</p>
      </div>

      <el-form ref="formRef" :model="form" :rules="rules" size="large" @submit.prevent>
        <el-form-item prop="phone">
          <el-input v-model="form.phone" placeholder="请输入手机号" maxlength="11" />
        </el-form-item>

        <el-form-item prop="code">
          <div class="code-row">
            <el-input v-model="form.code" placeholder="请输入验证码" maxlength="6" />
            <el-button
              class="code-btn"
              type="primary"
              plain
              :disabled="!canSend"
              :loading="sending"
              @click="handleSend"
            >
              {{ countdown > 0 ? `${countdown}s 后重发` : '获取验证码' }}
            </el-button>
          </div>
        </el-form-item>

        <el-button
          type="primary"
          class="login-btn"
          :loading="loading"
          @click="handleLogin"
        >
          登 录
        </el-button>
      </el-form>

      <p class="login-tip">演示环境：获取验证码后将自动回填，无需真实短信</p>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #e0ecff 0%, #f5f7fa 60%, #eafaf1 100%);
  padding: 24px;
}
.login-card {
  width: 400px;
  max-width: 100%;
  background: #fff;
  border-radius: 16px;
  box-shadow: 0 12px 40px rgba(64, 158, 255, 0.12);
  padding: 40px 32px 28px;
}
.login-head {
  text-align: center;
  margin-bottom: 28px;
}
.login-logo {
  font-size: 46px;
  line-height: 1;
}
.login-title {
  font-size: 22px;
  margin: 12px 0 6px;
  color: #303133;
}
.login-sub {
  font-size: 13px;
  color: #909399;
  margin: 0;
}
.code-row {
  display: flex;
  gap: 10px;
  width: 100%;
}
.code-btn {
  flex-shrink: 0;
  width: 128px;
}
.login-btn {
  width: 100%;
  margin-top: 6px;
}
.login-tip {
  text-align: center;
  font-size: 12px;
  color: #c0c4cc;
  margin: 20px 0 0;
}
</style>
