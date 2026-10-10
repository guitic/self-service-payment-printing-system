import axios, { type AxiosInstance, type AxiosRequestConfig, type AxiosResponse } from 'axios'
import { ElMessage } from 'element-plus'

/** 后端统一响应体：{ code, message, data } */
export interface ApiResult<T = unknown> {
  code: number
  message: string
  data: T
}

const TOKEN_KEY = 'sp_token'

export function getToken(): string {
  return localStorage.getItem(TOKEN_KEY) || ''
}
export function setToken(token: string): void {
  localStorage.setItem(TOKEN_KEY, token)
}
export function clearToken(): void {
  localStorage.removeItem(TOKEN_KEY)
}

/** 401 时清理 token 并跳转登录页（动态导入 router，避免循环依赖） */
function redirectToLogin(): void {
  clearToken()
  import('@/router').then(({ default: router }) => {
    if (router.currentRoute.value.path !== '/login') {
      router.push({ path: '/login', query: { redirect: router.currentRoute.value.fullPath } })
    }
  })
}

const service: AxiosInstance = axios.create({
  baseURL: '/api/v1',
  timeout: 15000,
})

// 请求拦截：注入 JWT
service.interceptors.request.use((config) => {
  const token = getToken()
  if (token) {
    config.headers = config.headers || {}
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

// 响应拦截：统一解包 {code,message,data}
service.interceptors.response.use(
  (response: AxiosResponse<ApiResult>) => {
    // 二进制流（文件下载）直接放行
    if (response.config.responseType === 'blob') {
      return response
    }
    const res = response.data
    if (!res || typeof res.code !== 'number') {
      // 非标准响应，原样返回
      return response.data as unknown as AxiosResponse
    }
    if (res.code !== 0) {
      ElMessage.error(res.message || '请求失败')
      if (res.code === 401) {
        redirectToLogin()
      }
      return Promise.reject(new Error(res.message || `业务错误 code=${res.code}`))
    }
    // 成功：直接返回 data，调用方拿到业务数据
    return res.data as unknown as AxiosResponse
  },
  (error) => {
    const status = error?.response?.status
    if (status === 401) {
      ElMessage.error('登录已过期，请重新登录')
      redirectToLogin()
    } else {
      const msg =
        error?.response?.data?.message || error?.message || '网络异常，请稍后重试'
      ElMessage.error(msg)
    }
    return Promise.reject(error)
  },
)

/** 统一请求方法集合（拦截器已解包，返回值即业务 data） */
export const http = {
  get<T = unknown>(url: string, params?: Record<string, unknown>): Promise<T> {
    return service.get(url, { params }) as unknown as Promise<T>
  },
  post<T = unknown>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
    return service.post(url, data, config) as unknown as Promise<T>
  },
  put<T = unknown>(url: string, data?: unknown): Promise<T> {
    return service.put(url, data) as unknown as Promise<T>
  },
  delete<T = unknown>(url: string, params?: Record<string, unknown>): Promise<T> {
    return service.delete(url, { params }) as unknown as Promise<T>
  },
  upload<T = unknown>(url: string, formData: FormData): Promise<T> {
    return service.post(url, formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
    }) as unknown as Promise<T>
  },
  /** 下载二进制流 */
  download(url: string, params?: Record<string, unknown>): Promise<AxiosResponse<Blob>> {
    return service.get(url, { params, responseType: 'blob' }) as unknown as Promise<
      AxiosResponse<Blob>
    >
  },
}

export default service
