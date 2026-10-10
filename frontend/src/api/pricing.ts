import { http } from './request'
import type { QuoteRequest, QuoteResult } from './types'

/** 实时试算：金额 = max(billablePages × copies × unitPrice × duplexDiscount, minCharge) */
export function quote(req: QuoteRequest): Promise<QuoteResult> {
  return http.post<QuoteResult>('/pricing/quote', req)
}
