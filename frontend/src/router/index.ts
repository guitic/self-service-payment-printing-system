import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { getToken } from '@/api/request'
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
        meta: { title: '管理端' },
      },
    ],
  },
  { path: '/:pathMatch(.*)*', redirect: '/upload' },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

// 路由守卫：未登录一律跳登录页
router.beforeEach((to) => {
  const logged = !!getToken()
  if (to.meta.public) {
    // 已登录访问登录页 -> 回到首页
    if (logged && to.path === '/login') return { path: '/upload' }
    return true
  }
  if (!logged) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  return true
})

router.afterEach((to) => {
  const title = (to.meta.title as string) || ''
  document.title = title ? `${title} · 自助付费打印系统` : '自助付费打印系统'
})

export default router
