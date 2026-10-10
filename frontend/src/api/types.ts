// 与 CONTRACT.md 严格对齐的类型定义（枚举 / 请求体 / 响应体）

export interface UserInfo {
  id: number
  phone: string
  nickname: string
}

/** 枚举 */
export type ColorMode = 'COLOR' | 'MONO'
export type DuplexMode = 'SIMPLEX' | 'DUPLEX'
export type PaperSize = 'A4' | 'A3'

/** payStatus: 0 待支付 / 1 已支付 / 2 已关闭 / 3 已退款 */
export const PAY_STATUS_TEXT: Record<number, string> = {
  0: '待支付',
  1: '已支付',
  2: '已关闭',
  3: '已退款',
}

/** printStatus: 0 待打印 / 1 打印中 / 2 已完成 / 3 失败 */
export const PRINT_STATUS_TEXT: Record<number, string> = {
  0: '待打印',
  1: '打印中',
  2: '已完成',
  3: '失败',
}

export interface FileInfo {
  id: number
  originalName: string
  contentType: string
  sizeBytes: number
  pageCount: number
}

export interface PageRange {
  from: number
  to: number
}

/** 计价 / 下单请求体（下单可额外带 terminalCode） */
export interface QuoteRequest {
  fileId: number
  copies: number
  colorMode: ColorMode
  duplexMode: DuplexMode
  paperSize: PaperSize
  pageRange?: PageRange | null
  terminalCode?: string
}

export interface QuoteResult {
  pageCount: number
  billablePages: number
  unitPrice: number
  duplexDiscount: number
  amount: number
  currency: string
  ruleId: number
}

export interface OrderParams {
  copies: number
  colorMode: ColorMode
  duplexMode: DuplexMode
  paperSize: PaperSize
  pageRange?: PageRange | null
}

export interface OrderInfo {
  orderNo: string
  amount: number
  payStatus: number
  printStatus: number
  payStatusText?: string
  printStatusText?: string
  expireAt: string
  fileId: number
  params?: OrderParams
  originalName?: string
  createdAt?: string
}

export interface PageResult<T> {
  list: T[]
  total: number
  page: number
  size: number
}

export interface PayInfo {
  orderNo: string
  gateway: string
  tradeNo: string
  qrCodeContent: string
  amount: number
  expireAt: string
}

export interface PayStatusInfo {
  orderNo: string
  payStatus: number
  payStatusText: string
}

export interface TaskStatusInfo {
  orderNo: string
  payStatus: number
  printStatus: number
  printStatusText: string
  failReason?: string
}

export interface PriceRule {
  id?: number
  name: string
  paperSize: PaperSize
  colorPrice: number
  monoPrice: number
  duplexDiscount: number
  minCharge: number
}

export interface Terminal {
  code: string
  name: string
  agentToken?: string
  online?: boolean
  lastHeartbeatAt?: string
}

export interface SmsSendResult {
  expireSeconds: number
  debugCode?: string
}

export interface LoginResult {
  token: string
  tokenType: string
  expiresIn: number
  user: UserInfo
}
