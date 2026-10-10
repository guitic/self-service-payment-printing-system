import { http } from './request'
import type { OrderInfo, PageResult, QuoteRequest } from './types'

/** 创建订单（请求体同 quote，可带 terminalCode） */
export function createOrder(req: QuoteRequest): Promise<OrderInfo> {
  return http.post<OrderInfo>('/orders', req)
}

/** 订单详情 */
export function getOrder(orderNo: string): Promise<OrderInfo> {
  return http.get<OrderInfo>(`/orders/${orderNo}`)
}

/** 我的订单列表（分页） */
export function listOrders(page = 1, size = 10): Promise<PageResult<OrderInfo>> {
  return http.get<PageResult<OrderInfo>>('/orders', { page, size })
}

/** 取消订单（仅未支付可取消） */
export function cancelOrder(orderNo: string): Promise<null> {
  return http.post<null>(`/orders/${orderNo}/cancel`)
}
