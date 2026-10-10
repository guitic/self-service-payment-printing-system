/** 字节数转可读大小 */
export function formatBytes(bytes?: number): string {
  if (!bytes || bytes <= 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB']
  let i = 0
  let v = bytes
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024
    i++
  }
  return `${v.toFixed(i === 0 ? 0 : 2)} ${units[i]}`
}

/** 金额格式化：保留两位小数 */
export function formatAmount(v?: number | string | null): string {
  if (v === null || v === undefined || v === '') return '0.00'
  return Number(v).toFixed(2)
}

/** ISO-8601 字符串 -> 本地可读时间 */
export function formatDateTime(s?: string | null): string {
  if (!s) return '-'
  const d = new Date(s)
  if (Number.isNaN(d.getTime())) return s
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(
    d.getMinutes(),
  )}:${pad(d.getSeconds())}`
}

/** 枚举中文映射 */
export const COLOR_MODE_LABEL: Record<string, string> = {
  COLOR: '彩色',
  MONO: '黑白',
}
export const DUPLEX_MODE_LABEL: Record<string, string> = {
  SIMPLEX: '单面',
  DUPLEX: '双面',
}
export const PAPER_SIZE_LABEL: Record<string, string> = {
  A4: 'A4',
  A3: 'A3',
}
