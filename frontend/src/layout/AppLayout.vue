<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { useUserStore } from '@/stores/user'
import { useOrderStore } from '@/stores/order'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const orderStore = useOrderStore()

const navs = [
  { path: '/upload', label: '上传文件' },
  { path: '/orders', label: '我的订单' },
  { path: '/admin', label: '管理端' },
]

// 高亮当前一级菜单（pay/status 归入订单流程）
const activeNav = computed(() => {
  const p = route.path
  if (p.startsWith('/pay') || p.startsWith('/status') || p.startsWith('/params')) return '/upload'
  return navs.find((n) => p.startsWith(n.path))?.path || '/upload'
})

async function handleLogout() {
  await ElMessageBox.confirm('确定退出登录吗？', '提示', { type: 'warning' })
  await userStore.logout()
  orderStore.reset()
  router.push('/login')
}
</script>

<template>
  <div class="layout">
    <header class="layout-header">
      <div class="brand" @click="router.push('/upload')">
        <span class="brand-logo">🖨️</span>
        <span class="brand-name">自助付费打印</span>
      </div>

      <el-menu :default-active="activeNav" mode="horizontal" router class="nav-menu">
        <el-menu-item v-for="n in navs" :key="n.path" :index="n.path">
          {{ n.label }}
        </el-menu-item>
      </el-menu>

      <div class="user-area">
        <el-dropdown>
          <span class="user-trigger">
            <el-avatar :size="28">{{ (userStore.user?.nickname || '用户')[0] }}</el-avatar>
            <span class="user-name">{{ userStore.user?.nickname || userStore.user?.phone || '用户' }}</span>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item disabled>{{ userStore.user?.phone || '-' }}</el-dropdown-item>
              <el-dropdown-item divided @click="handleLogout">退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </header>

    <main class="layout-main">
      <router-view />
    </main>
  </div>
</template>

<style scoped>
.layout {
  min-height: 100%;
  display: flex;
  flex-direction: column;
}
.layout-header {
  display: flex;
  align-items: center;
  gap: 24px;
  height: 60px;
  padding: 0 24px;
  background: #fff;
  border-bottom: 1px solid #ebeef5;
  position: sticky;
  top: 0;
  z-index: 10;
}
.brand {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  font-weight: 700;
  font-size: 17px;
  white-space: nowrap;
}
.brand-logo {
  font-size: 22px;
}
.nav-menu {
  flex: 1;
  border-bottom: none !important;
}
.user-area {
  white-space: nowrap;
}
.user-trigger {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  outline: none;
}
.user-name {
  font-size: 14px;
  color: #606266;
}
.layout-main {
  flex: 1;
}
</style>
