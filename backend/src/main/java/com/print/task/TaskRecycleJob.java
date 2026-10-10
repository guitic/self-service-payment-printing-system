package com.print.task;

import com.print.order.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 后台定时任务：
 * <ul>
 *   <li>任务超时回收（ADR-006）</li>
 *   <li>终端离线检测</li>
 *   <li>未支付订单超时关闭</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskRecycleJob {

    private final TaskService taskService;
    private final TerminalService terminalService;
    private final OrderService orderService;

    /** 每 30s 回收超时未回传的打印任务。 */
    @Scheduled(initialDelay = 30_000, fixedDelay = 30_000)
    public void recycleTimeoutTasks() {
        try {
            taskService.recycleTimeouts();
        } catch (Exception e) {
            log.error("任务超时回收失败", e);
        }
    }

    /** 每 30s 刷新终端在线状态。 */
    @Scheduled(initialDelay = 30_000, fixedDelay = 30_000)
    public void refreshTerminalStatus() {
        try {
            terminalService.markOffline();
        } catch (Exception e) {
            log.error("终端离线检测失败", e);
        }
    }

    /** 每 60s 关闭超时未支付订单。 */
    @Scheduled(initialDelay = 60_000, fixedDelay = 60_000)
    public void closeExpiredOrders() {
        try {
            orderService.closeExpiredOrders();
        } catch (Exception e) {
            log.error("关闭超时订单失败", e);
        }
    }
}
