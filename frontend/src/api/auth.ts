import { http } from './request'
import type { LoginResult, SmsSendResult, UserInfo } from './types'

/** 发送短信验证码（demo 档返回 debugCode 便于联调） */
export function sendSms(phone: string): Promise<SmsSendResult> {
  return http.post<SmsSendResult>('/auth/sms/send', { phone })
}

/** 手机号 + 验证码登录 */
export function login(phone: string, code: string): Promise<LoginResult> {
  return http.post<LoginResult>('/auth/login', { phone, code })
}

/** 获取当前登录用户 */
export function getMe(): Promise<UserInfo> {
  return http.get<UserInfo>('/auth/me')
}

/** 退出登录 */
export function logout(): Promise<null> {
  return http.post<null>('/auth/logout')
}
