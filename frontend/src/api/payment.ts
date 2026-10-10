import { http } from './request'
import type { PayInfo, PayStatusInfo } from './types'

/** 发起支付，返回二维码内容 qrCodeContent */
export function createPay(orderNo: string): Promise<PayInfo> {
  return http.post<PayInfo>(`/payments/${orderNo}/pay`)
}

/** 轮询支付状态 */
export function getPayStatus(orderNo: string): Promise<PayStatusInfo> {
  return http.get<PayStatusInfo>(`/payments/${orderNo}/status`)
}

/** demo 档：模拟支付回调（result: SUCCESS / FAIL） */
export function mockConfirm(
  orderNo: string,
  result: 'SUCCESS' | 'FAIL' = 'SUCCESS',
): Promise<{ orderNo: string; payStatus: number }> {
  return http.post<{ orderNo: string; payStatus: number }>('/payments/mock/confirm', {
    orderNo,
    result,
  })
}
