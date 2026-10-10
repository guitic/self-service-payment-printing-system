package com.print.task;

import com.print.file.entity.PrintFile;
import com.print.order.OrderStatus;
import com.print.order.entity.PrintOrder;
import com.print.support.AbstractIntegrationTest;
import com.print.task.entity.PrintTask;
import com.print.task.entity.Terminal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 打印任务服务集成测试：回传幂等、失败重试上限、超时回收、原子领取（ADR-006 / TC-TASK）。
 */
class TaskServiceTest extends AbstractIntegrationTest {

    @Autowired
    private TaskService taskService;

    @Test
    @DisplayName("TC-TASK-05/09 回传成功幂等：重复成功只生效一次，订单置已完成")
    void reportSuccessIsIdempotent() {
        PrintFile f = newFile(1L, 5);
        PrintOrder o = newOrder(1L, f.getId(), new BigDecimal("2.00"), OrderStatus.PAY_PAID);
        PrintTask t = newTask(o.getOrderNo(), null, TaskStatus.RUNNING, 0, LocalDateTime.now());

        taskService.report(t.getId(), "SUCCESS", null);

        PrintTask after = taskMapper.selectById(t.getId());
        assertThat(after.getStatus()).isEqualTo(TaskStatus.SUCCESS);
        assertThat(after.getFinishedAt()).isNotNull();
        assertThat(orderMapper.selectById(o.getId()).getPrintStatus()).isEqualTo(OrderStatus.PRINT_DONE);

        // 重复回传成功：状态不变、无副作用
        taskService.report(t.getId(), "SUCCESS", null);
        assertThat(taskMapper.selectById(t.getId()).getStatus()).isEqualTo(TaskStatus.SUCCESS);
    }

    @Test
    @DisplayName("TC-TASK-06/07 失败按上限重试，达上限后转终态失败")
    void reportFailureRetriesUntilTerminalFail() {
        PrintFile f = newFile(1L, 5);
        PrintOrder o = newOrder(1L, f.getId(), new BigDecimal("2.00"), OrderStatus.PAY_PAID);
        PrintTask t = newTask(o.getOrderNo(), null, TaskStatus.RUNNING, 0, LocalDateTime.now());

        // 第 1 次失败：重试中（PENDING, retry=1）
        taskService.report(t.getId(), "FAILED", "缺纸");
        assertThat(taskMapper.selectById(t.getId()).getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(taskMapper.selectById(t.getId()).getRetryCount()).isEqualTo(1);

        // 第 2 次失败：仍重试（retry=2）
        taskService.report(t.getId(), "FAILED", "缺纸");
        assertThat(taskMapper.selectById(t.getId()).getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(taskMapper.selectById(t.getId()).getRetryCount()).isEqualTo(2);

        // 第 3 次失败：达上限 -> 终态失败
        taskService.report(t.getId(), "FAILED", "缺纸");
        PrintTask after = taskMapper.selectById(t.getId());
        assertThat(after.getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(after.getRetryCount()).isEqualTo(3);
        assertThat(orderMapper.selectById(o.getId()).getPrintStatus()).isEqualTo(OrderStatus.PRINT_FAILED);
    }

    @Test
    @DisplayName("TC-TASK-08 超时回收：领取超时任务退回待领取并重试计数 +1")
    void recycleTimeoutTask() {
        PrintFile f = newFile(1L, 5);
        PrintOrder o = newOrder(1L, f.getId(), new BigDecimal("2.00"), OrderStatus.PAY_PAID);
        // 领取时间早于阈值（claimTimeoutSeconds=120s）
        PrintTask t = newTask(o.getOrderNo(), null, TaskStatus.RUNNING, 0,
                LocalDateTime.now().minusSeconds(200));

        int recycled = taskService.recycleTimeouts();

        assertThat(recycled).isGreaterThanOrEqualTo(1);
        PrintTask after = taskMapper.selectById(t.getId());
        assertThat(after.getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(after.getRetryCount()).isEqualTo(1);
        assertThat(after.getClaimedAt()).isNull();
    }

    @Test
    @DisplayName("超时回收：已达重试上限的任务转终态失败")
    void recycleTimeoutAtMaxRetryFailsTerminally() {
        PrintFile f = newFile(1L, 5);
        PrintOrder o = newOrder(1L, f.getId(), new BigDecimal("2.00"), OrderStatus.PAY_PAID);
        // retryCount=2，回收后 retry=3 不小于上限(3) -> FAILED
        PrintTask t = newTask(o.getOrderNo(), null, TaskStatus.RUNNING, 2,
                LocalDateTime.now().minusSeconds(200));

        taskService.recycleTimeouts();

        PrintTask after = taskMapper.selectById(t.getId());
        assertThat(after.getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(after.getRetryCount()).isEqualTo(3);
        assertThat(orderMapper.selectById(o.getId()).getPrintStatus()).isEqualTo(OrderStatus.PRINT_FAILED);
    }

    @Test
    @DisplayName("TC-TASK-10 原子领取：同一任务只能被领取一次")
    void claimOnlyOnce() {
        PrintFile f = newFile(1L, 5);
        PrintOrder o = newOrder(1L, f.getId(), new BigDecimal("2.00"), OrderStatus.PAY_PAID);
        PrintTask t = newTask(o.getOrderNo(), null, TaskStatus.PENDING, 0, null);

        Terminal terminal = new Terminal();
        terminal.setId(1L);

        TaskService.TaskVO first = taskService.claim(terminal, 0);
        assertThat(first).isNotNull();
        assertThat(first.taskId()).isEqualTo(t.getId());

        // 已被领取（RUNNING），第二次领取返回空
        TaskService.TaskVO second = taskService.claim(terminal, 0);
        assertThat(second).isNull();
        assertThat(taskMapper.selectById(t.getId()).getStatus()).isEqualTo(TaskStatus.RUNNING);
    }
}
