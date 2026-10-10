import { http } from './request'
import type { OrderInfo, PageResult, PriceRule, Terminal } from './types'

/* ---------- 计价规则 ---------- */
export function listPriceRules(): Promise<PriceRule[]> {
  return http.get<PriceRule[]>('/admin/price-rules')
}
export function createPriceRule(rule: PriceRule): Promise<PriceRule> {
  return http.post<PriceRule>('/admin/price-rules', rule)
}
export function updatePriceRule(id: number, rule: PriceRule): Promise<PriceRule> {
  return http.put<PriceRule>(`/admin/price-rules/${id}`, rule)
}

/* ---------- 订单 ---------- */
export function adminListOrders(page = 1, size = 10): Promise<PageResult<OrderInfo>> {
  return http.get<PageResult<OrderInfo>>('/admin/orders', { page, size })
}

/* ---------- 终端 ---------- */
export function listTerminals(): Promise<Terminal[]> {
  return http.get<Terminal[]>('/admin/terminals')
}
export function createTerminal(data: {
  code: string
  name: string
  agentToken: string
}): Promise<Terminal> {
  return http.post<Terminal>('/admin/terminals', data)
}
