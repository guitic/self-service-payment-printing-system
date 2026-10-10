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

  return { token, user, isLoggedIn, login, fetchMe, logout, reset }
})
