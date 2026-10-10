package com.print.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.print.common.BizException;
import com.print.common.ResultCode;
import com.print.config.PrintProperties;
import com.print.file.FileService;
import com.print.file.entity.PrintFile;
import com.print.order.OrderService;
import com.print.order.OrderStatus;
import com.print.task.entity.PrintTask;
import com.print.task.entity.Terminal;
import com.print.task.mapper.PrintTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 打印任务服务：投递、原子领取（长轮询）、结果回传、超时回收。
 *
 * <p>一致性要点（CONTRACT.md §5）：
 * <ul>
 *   <li>任务与订单 1:1，唯一索引兜底防重复投递</li>
 *   <li>领取用条件更新（status 0→2）保证同一任务只被一个助手领到</li>
 *   <li>领取后超过 claimTimeoutSeconds 未回传则回收重试，超上限转终态失败</li>
 *   <li>回传成功幂等，且成功状态不可被回收覆盖</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskService {

    private final PrintTaskMapper taskMapper;
    private final TerminalService terminalService;
    private final OrderService orderService;
    private final FileService fileService;
    private final PrintProperties props;
    private final ObjectMapper objectMapper;

    /** 任务下发参数快照。 */
    public record TaskParams(Integer copies, String colorMode, String duplexMode, String paperSize,
                             Integer pageCount, Long fileId, String fileName, Long fileSize) {
    }

    public record TaskVO(Long taskId, String orderNo, String terminalCode, String printerName,
                         String fileName, Long fileSize, Integer pageCount, Integer copies,
                         String colorMode, String duplexMode, String paperSize, Integer retryCount) {
    }

    public record TaskStatusVO(String orderNo, Integer payStatus, String payStatusText,
                               Integer printStatus, String printStatusText, String failReason) {
    }

    /** 支付成功后投递打印任务（幂等）。 */
    @Transactional
    public void enqueue(String orderNo, Long terminalId) {
        PrintTask exist = taskMapper.selectOne(new LambdaQueryWrapper<PrintTask>()
                .eq(PrintTask::getOrderNo, orderNo));
        if (exist != null) {
            log.info("任务已存在，跳过重复投递: orderNo={}", orderNo);
            return;
        }
        var order = orderService.findByOrderNo(orderNo);
        PrintFile file = fileService.findMeta(order.getFileId());

        TaskParams params = new TaskParams(
                extractInt(order.getParamsJson(), "copies", 1),
                extractStr(order.getParamsJson(), "colorMode", "MONO"),
                extractStr(order.getParamsJson(), "duplexMode", "SIMPLEX"),
                extractStr(order.getParamsJson(), "paperSize", "A4"),
                order.getPageCount(),
                order.getFileId(),
                file == null ? "unknown" : file.getOriginalName(),
                file == null || file.getSizeBytes() == null ? 0L : file.getSizeBytes());

        LocalDateTime now = LocalDateTime.now();
        PrintTask t = new PrintTask();
        t.setOrderNo(orderNo);
        t.setTerminalId(terminalId != null ? terminalId : order.getTerminalId());
        t.setFilePath(file == null ? null : file.getStoragePath());
        t.setParamsJson(toJson(params));
        t.setStatus(TaskStatus.PENDING);
        t.setRetryCount(0);
        t.setCreatedAt(now);
        t.setUpdatedAt(now);
        taskMapper.insert(t);
        log.info("打印任务已投递: taskId={}, orderNo={}, terminalId={}", t.getId(), orderNo, t.getTerminalId());
    }

    /**
     * 助手领取任务（长轮询）。原子条件更新，保证同一任务只被领取一次。
     *
     * @return 任务或 null（超时无任务）
     */
    public TaskVO claim(Terminal terminal, int longPollSeconds) {
        long deadline = System.currentTimeMillis() + Math.max(longPollSeconds, 0) * 1000L;
        while (true) {
            PrintTask claimed = tryClaimOne(terminal.getId());
            if (claimed != null) {
                orderService.updatePrintStatus(claimed.getOrderNo(), OrderStatus.PRINT_RUNNING);
                return toVO(claimed);
            }
            if (System.currentTimeMillis() >= deadline) {
                return null;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
    }

    private PrintTask tryClaimOne(Long terminalId) {
        PrintTask candidate = taskMapper.selectOne(new LambdaQueryWrapper<PrintTask>()
                .eq(PrintTask::getStatus, TaskStatus.PENDING)
                .and(w -> w.isNull(PrintTask::getTerminalId).or().eq(PrintTask::getTerminalId, terminalId))
                .orderByAsc(PrintTask::getId)
                .last("LIMIT 1"));
        if (candidate == null) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        int rows = taskMapper.update(null, new LambdaUpdateWrapper<PrintTask>()
                .eq(PrintTask::getId, candidate.getId())
                .eq(PrintTask::getStatus, TaskStatus.PENDING)
                .set(PrintTask::getStatus, TaskStatus.RUNNING)
                .set(PrintTask::getClaimedAt, now)
                .set(PrintTask::getUpdatedAt, now));
        if (rows == 1) {
            candidate.setStatus(TaskStatus.RUNNING);
            candidate.setClaimedAt(now);
            log.info("任务被领取: taskId={}, orderNo={}", candidate.getId(), candidate.getOrderNo());
            return candidate;
        }
        return null;
    }

    /** 助手回传打印结果（幂等）。 */
    @Transactional
    public void report(Long taskId, String status, String failReason) {
        PrintTask t = taskMapper.selectById(taskId);
        if (t == null) {
            throw new BizException(ResultCode.NOT_FOUND.code(), "任务不存在");
        }
        if (Objects.equals(t.getStatus(), TaskStatus.SUCCESS)) {
            log.info("任务已是成功态，忽略重复回传: taskId={}", taskId);
            return;
        }
        LocalDateTime now = LocalDateTime.now();

        if ("SUCCESS".equalsIgnoreCase(status)) {
            int rows = taskMapper.update(null, new LambdaUpdateWrapper<PrintTask>()
                    .eq(PrintTask::getId, taskId)
                    .ne(PrintTask::getStatus, TaskStatus.SUCCESS)
                    .set(PrintTask::getStatus, TaskStatus.SUCCESS)
                    .set(PrintTask::getFailReason, null)
                    .set(PrintTask::getFinishedAt, now)
                    .set(PrintTask::getUpdatedAt, now));
            if (rows == 1) {
                orderService.updatePrintStatus(t.getOrderNo(), OrderStatus.PRINT_DONE);
                log.info("打印成功: taskId={}, orderNo={}", taskId, t.getOrderNo());
            }
            return;
        }

        // 失败：重试或转终态失败
        int retry = (t.getRetryCount() == null ? 0 : t.getRetryCount()) + 1;
        boolean canRetry = retry < props.getTask().getMaxRetry();
        int newStatus = canRetry ? TaskStatus.PENDING : TaskStatus.FAILED;
        int rows = taskMapper.update(null, new LambdaUpdateWrapper<PrintTask>()
                .eq(PrintTask::getId, taskId)
                .ne(PrintTask::getStatus, TaskStatus.SUCCESS)
                .set(PrintTask::getStatus, newStatus)
                .set(PrintTask::getRetryCount, retry)
                .set(PrintTask::getFailReason, failReason)
                .set(PrintTask::getClaimedAt, null)
                .set(PrintTask::getUpdatedAt, now));
        if (rows == 1) {
            if (canRetry) {
                log.warn("打印失败，将重试({}/{}): taskId={}, reason={}",
                        retry, props.getTask().getMaxRetry() - 1, taskId, failReason);
            } else {
                orderService.updatePrintStatus(t.getOrderNo(), OrderStatus.PRINT_FAILED);
                log.error("打印失败已达重试上限，转终态失败: taskId={}, orderNo={}, reason={}",
                        taskId, t.getOrderNo(), failReason);
            }
        }
    }

    /**
     * 助手回传入口：先校验任务归属终端，再委托 {@link #report}。
     *
     * <p>安全：{@code /agent/tasks/{id}/report} 仅校验令牌有效性是不够的——
     * 任一持有效令牌的终端不得改写**其它终端**的任务状态，故此处强制归属校验。
     */
    @Transactional
    public void reportByAgent(Long taskId, String status, String failReason, Terminal terminal) {
        PrintTask t = taskMapper.selectById(taskId);
        if (t == null) {
            throw new BizException(ResultCode.NOT_FOUND.code(), "任务不存在");
        }
        if (t.getTerminalId() != null && terminal != null
                && !Objects.equals(t.getTerminalId(), terminal.getId())) {
            log.warn("越权回传被拒: taskId={}, 任务终端={}, 请求终端={}",
                    taskId, t.getTerminalId(), terminal.getId());
            throw new BizException(ResultCode.FORBIDDEN.code(), "该任务不属于当前终端");
        }
        report(taskId, status, failReason);
    }

    /** 超时回收：领取后长时间未回传的任务退回待领取（ADR-006）。 */
    public int recycleTimeouts() {
        LocalDateTime threshold = LocalDateTime.now().minusSeconds(props.getTask().getClaimTimeoutSeconds());
        List<PrintTask> stuck = taskMapper.selectList(new LambdaQueryWrapper<PrintTask>()
                .eq(PrintTask::getStatus, TaskStatus.RUNNING)
                .lt(PrintTask::getClaimedAt, threshold));
        int recycled = 0;
        for (PrintTask t : stuck) {
            int retry = (t.getRetryCount() == null ? 0 : t.getRetryCount()) + 1;
            boolean canRetry = retry < props.getTask().getMaxRetry();
            int newStatus = canRetry ? TaskStatus.PENDING : TaskStatus.FAILED;
            int rows = taskMapper.update(null, new LambdaUpdateWrapper<PrintTask>()
                    .eq(PrintTask::getId, t.getId())
                    .eq(PrintTask::getStatus, TaskStatus.RUNNING)
                    .set(PrintTask::getStatus, newStatus)
                    .set(PrintTask::getRetryCount, retry)
                    .set(PrintTask::getFailReason, "任务超时未回传，已回收")
                    .set(PrintTask::getClaimedAt, null)
                    .set(PrintTask::getUpdatedAt, LocalDateTime.now()));
            if (rows == 1) {
                recycled++;
                if (!canRetry) {
                    orderService.updatePrintStatus(t.getOrderNo(), OrderStatus.PRINT_FAILED);
                }
                log.warn("任务超时回收: taskId={}, orderNo={}, 新状态={}", t.getId(), t.getOrderNo(), newStatus);
            }
        }
        return recycled;
    }

    /** 用户查询打印状态。 */
    public TaskStatusVO statusByOrderNo(String orderNo, Long userId) {
        var order = orderService.requireOwned(orderNo, userId);
        PrintTask t = taskMapper.selectOne(new LambdaQueryWrapper<PrintTask>()
                .eq(PrintTask::getOrderNo, orderNo));
        return new TaskStatusVO(orderNo, order.getPayStatus(), OrderStatus.payText(order.getPayStatus()),
                order.getPrintStatus(), OrderStatus.printText(order.getPrintStatus()),
                t == null ? null : t.getFailReason());
    }

    /** 助手下载待打印文件（校验任务归属终端）。 */
    public Resource taskFile(Long taskId, Terminal terminal) {
        PrintTask t = taskMapper.selectById(taskId);
        if (t == null) {
            throw new BizException(ResultCode.NOT_FOUND.code(), "任务不存在");
        }
        if (t.getTerminalId() != null && !Objects.equals(t.getTerminalId(), terminal.getId())) {
            throw new BizException(ResultCode.FORBIDDEN);
        }
        PrintFile file = fileService.findMetaByStoragePath(t.getFilePath());
        if (file == null) {
            throw new BizException(ResultCode.NOT_FOUND.code(), "任务文件不存在");
        }
        return fileService.loadResource(file);
    }

    private TaskVO toVO(PrintTask t) {
        TaskParams p = parseParams(t.getParamsJson());
        String terminalCode = null;
        if (t.getTerminalId() != null) {
            terminalCode = terminalService.list().stream()
                    .filter(v -> Objects.equals(v.id(), t.getTerminalId()))
                    .map(TerminalService.TerminalVO::code)
                    .findFirst().orElse(null);
        }
        return new TaskVO(t.getId(), t.getOrderNo(), terminalCode, "Default Printer",
                p == null ? null : p.fileName(),
                (p == null || p.fileSize() == null) ? 0L : p.fileSize(),
                (p == null || p.pageCount() == null) ? 0 : p.pageCount(),
                (p == null || p.copies() == null) ? 1 : p.copies(),
                (p == null || p.colorMode() == null) ? "MONO" : p.colorMode(),
                (p == null || p.duplexMode() == null) ? "SIMPLEX" : p.duplexMode(),
                (p == null || p.paperSize() == null) ? "A4" : p.paperSize(),
                t.getRetryCount());
    }

    private TaskParams parseParams(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, TaskParams.class);
        } catch (Exception e) {
            return null;
        }
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }

    private Integer extractInt(String json, String key, int def) {
        try {
            var node = objectMapper.readTree(json);
            return node.has(key) && !node.get(key).isNull() ? node.get(key).asInt() : def;
        } catch (Exception e) {
            return def;
        }
    }

    private String extractStr(String json, String key, String def) {
        try {
            var node = objectMapper.readTree(json);
            return node.has(key) && !node.get(key).isNull() ? node.get(key).asText() : def;
        } catch (Exception e) {
            return def;
        }
    }
}
