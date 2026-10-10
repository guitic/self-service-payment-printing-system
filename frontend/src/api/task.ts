import { http } from './request'
import type { TaskStatusInfo } from './types'

/** 查询打印任务状态（用于打印状态轮询） */
export function getTaskStatus(orderNo: string): Promise<TaskStatusInfo> {
  return http.get<TaskStatusInfo>(`/tasks/${orderNo}/status`)
}
