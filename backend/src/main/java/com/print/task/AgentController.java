package com.print.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.print.common.Result;
import com.print.config.PrintProperties;
import com.print.task.entity.Terminal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 打印助手接口（CONTRACT.md §3.6）。
 * 鉴权走 {@code X-Agent-Token}，由 {@link TerminalService#requireByToken} 校验。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/agent")
@RequiredArgsConstructor
public class AgentController {

    private static final String TOKEN_HEADER = "X-Agent-Token";

    private final TerminalService terminalService;
    private final TaskService taskService;
    private final PrintProperties props;
    private final ObjectMapper objectMapper;

    public record PrinterInfo(String name, String status) {
    }

    public record HeartbeatReq(String terminalCode, String agentVersion, List<PrinterInfo> printers) {
    }

    public record HeartbeatConfig(int claimTimeoutSeconds, int maxRetry, int heartbeatSeconds) {
    }

    public record HeartbeatResp(String serverTime, HeartbeatConfig config) {
    }

    public record ClaimReq(String terminalCode, Integer longPollSeconds) {
    }

    public record ReportReq(String status, String failReason) {
    }

    @PostMapping("/heartbeat")
    public Result<HeartbeatResp> heartbeat(@RequestHeader(value = TOKEN_HEADER, required = false) String token,
                                           @RequestBody(required = false) HeartbeatReq req) {
        Terminal t = terminalService.requireByToken(token);
        String printersJson = req == null || req.printers() == null ? "[]" : toJson(req.printers());
        terminalService.heartbeat(t, printersJson);
        return Result.ok(new HeartbeatResp(
                LocalDateTime.now().toString(),
                new HeartbeatConfig(props.getTask().getClaimTimeoutSeconds(),
                        props.getTask().getMaxRetry(), 30)));
    }

    @PostMapping("/tasks/claim")
    public Result<TaskService.TaskVO> claim(@RequestHeader(value = TOKEN_HEADER, required = false) String token,
                                            @RequestBody(required = false) ClaimReq req) {
        Terminal t = terminalService.requireByToken(token);
        int longPoll = (req == null || req.longPollSeconds() == null)
                ? props.getTask().getLongPollSeconds() : req.longPollSeconds();
        return Result.ok(taskService.claim(t, longPoll));
    }

    @PostMapping("/tasks/{id}/report")
    public Result<Void> report(@RequestHeader(value = TOKEN_HEADER, required = false) String token,
                               @PathVariable Long id,
                               @RequestBody ReportReq req) {
        Terminal t = terminalService.requireByToken(token);
        taskService.reportByAgent(id, req.status(), req.failReason(), t);
        return Result.ok();
    }

    @GetMapping("/tasks/{id}/file")
    public ResponseEntity<Resource> file(@RequestHeader(value = TOKEN_HEADER, required = false) String token,
                                         @PathVariable Long id) {
        Terminal t = terminalService.requireByToken(token);
        Resource resource = taskService.taskFile(id, t);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).body(resource);
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return "[]";
        }
    }
}
