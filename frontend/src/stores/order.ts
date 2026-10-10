import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { FileInfo, OrderParams, QuoteResult } from '@/api/types'

/**
 * 下单上下文：贯穿 上传 -> 参数 -> 试算 -> 下单 -> 支付 的会话数据
 */
export const useOrderStore = defineStore('order', () => {
  /** 已上传文件信息 */
  const fileInfo = ref<FileInfo | null>(null)
  /** 打印参数 */
  const params = ref<OrderParams>({
    copies: 1,
    colorMode: 'MONO',
    duplexMode: 'SIMPLEX',
    paperSize: 'A4',
    pageRange: null,
  })
  /** 最近一次试算结果 */
  const quote = ref<QuoteResult | null>(null)
  /** 当前订单号 */
  const orderNo = ref<string>('')

  const fileId = ref<number | null>(null)

  function setFile(info: FileInfo): void {
    fileInfo.value = info
    fileId.value = info.id
  }

  function reset(): void {
    fileInfo.value = null
    fileId.value = null
    orderNo.value = ''
    quote.value = null
    params.value = {
      copies: 1,
      colorMode: 'MONO',
      duplexMode: 'SIMPLEX',
      paperSize: 'A4',
      pageRange: null,
    }
  }

  return { fileInfo, fileId, params, quote, orderNo, setFile, reset }
})
