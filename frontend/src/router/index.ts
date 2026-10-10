import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getToken } from '@/api/request'
import { useUserStore } from '@/stores/user'
import AppLayout from '@/layout/AppLayout.vue'

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: { title: '登录', public: true },
  },
  {
    path: '/',
    component: AppLayout,
    redirect: '/upload',
    children: [
      {
        path: 'upload',
        name: 'upload',
        component: () => import('@/views/UploadView.vue'),
        meta: { title: '上传文件' },
      },
      {
        path: 'params',
        name: 'params',
        component: () => import('@/views/ParamsView.vue'),
        meta: { title: '打印参数' },
      },
      {
        path: 'pay/:orderNo',
        name: 'pay',
        component: () => import('@/views/PayView.vue'),
        meta: { title: '扫码支付' },
      },
      {
        path: 'status/:orderNo',
        name: 'status',
        component: () => import('@/views/StatusView.vue'),
        meta: { title: '打印状态' },
      },
      {
        path: 'orders',
        name: 'orders',
        component: () => import('@/views/OrdersView.vue'),
        meta: { title: '我的订单' },
      },
      {
        path: 'admin',
        name: 'admin',
        component: () => import('@/views/AdminView.vue'),
        meta: { title: '管理端', requiresAdmin: true },
      },
    ],
  },
  { path: '/:pathMatch(.*)*', redirect: '/upload' },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

// 路由守卫：
//  1) 未登录 → 跳登录页（带 redirect）
//  2) 需管理员的路由 → 校验角色，非管理员提示并送回首页
// 注意：前端守卫只负责体验，真正的权限边界在后端 /api/v1/admin/** 的 hasRole("ADMIN")。
router.beforeEach(async (to) => {
  const logged = !!getToken()
  if (to.meta.public) {
    // 已登录访问登录页 -> 回到首页
    if (logged && to.path === '/login') return { path: '/upload' }
    return true
  }
  if (!logged) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (to.meta.requiresAdmin) {
    // 硬刷新时 store 可能为空，先确保用户信息已加载再判定角色
    const userStore = useUserStore()
    await userStore.ensureUser()
    if (!userStore.isAdmin) {
      ElMessage.warning('无权访问管理端')
      return { path: '/upload' }
    }
  }
  return true
})

router.afterEach((to) => {
  const title = (to.meta.title as string) || ''
  document.title = title ? `${title} · 自助付费打印系统` : '自助付费打印系统'
})

export default router
