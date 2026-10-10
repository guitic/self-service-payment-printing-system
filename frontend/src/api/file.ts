import { http } from './request'
import type { FileInfo } from './types'

/** 上传文件（multipart，字段名 file） */
export function uploadFile(file: File): Promise<FileInfo> {
  const fd = new FormData()
  fd.append('file', file)
  return http.upload<FileInfo>('/files', fd)
}

/** 查询文件信息 */
export function getFile(id: number): Promise<FileInfo> {
  return http.get<FileInfo>(`/files/${id}`)
}

/** 文件下载地址（浏览器直接打开） */
export function fileDownloadUrl(id: number): string {
  return `/api/v1/files/${id}/download`
}
