import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { getMe, login as apiLogin, logout as apiLogout } from '@/api/auth'
import type { LoginResult, UserInfo } from '@/api/types'
import { clearToken, getToken, setToken } from '@/api/request'

/**
 * 用户状态：token 持久化到 localStorage
 */
export const useUserStore = defineStore('user', () => {
  const token = ref<string>(getToken())
  const user = ref<UserInfo | null>(null)

  const isLoggedIn = computed(() => !!token.value)
  /** 是否管理员（由后端下发的 role 决定；前端仅用于控制入口与路由，不作为安全边界） */
  const isAdmin = computed(() => user.value?.role === 'ADMIN')

  async function login(phone: string, code: string): Promise<LoginResult> {
    const res = await apiLogin(phone, code)
    token.value = res.token
    setToken(res.token)
    user.value = res.user
    return res
  }

  /** 应用启动 / 刷新后拉取用户信息 */
  async function fetchMe(): Promise<void> {
    if (!token.value) return
    try {
      user.value = await getMe()
    } catch {
      // 失败时由拦截器统一处理（401 会跳登录）
    }
  }

  /**
   * 确保用户信息已加载（路由守卫用）。
   *
   * <p>硬刷新时 store 为空但 token 仍在，必须先拉一次 `/auth/me` 才能判定角色，
   * 否则会把管理员误判为普通用户。
   */
  async function ensureUser(): Promise<void> {
    if (user.value || !token.value) return
    await fetchMe()
  }

  async function logout(): Promise<void> {
    try {
      await apiLogout()
    } catch {
      // 忽略退出接口异常
    }
    token.value = ''
    user.value = null
    clearToken()
  }

  function reset(): void {
    token.value = ''
    user.value = null
    clearToken()
  }

  return { token, user, isLoggedIn, isAdmin, login, fetchMe, ensureUser, logout, reset }
})
