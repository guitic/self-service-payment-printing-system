import { http } from './request'
import type { QuoteRequest, QuoteResult } from './types'

/** 实时试算：金额 = 计费页数 × 份数 × 单价 × 双面折扣（按实际数量直接计算，不设起印价） */
export function quote(req: QuoteRequest): Promise<QuoteResult> {
  return http.post<QuoteResult>('/pricing/quote', req)
}
