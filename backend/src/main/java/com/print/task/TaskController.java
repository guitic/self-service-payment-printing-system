package com.print.task;

import com.print.common.Result;
import com.print.config.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户侧打印状态查询（CONTRACT.md §3.6）。
 */
@RestController
@RequestMapping("/api/v1/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;

    @GetMapping("/{orderNo}/status")
    public Result<TaskService.TaskStatusVO> status(@PathVariable String orderNo) {
        return Result.ok(taskService.statusByOrderNo(orderNo, UserContext.userId()));
    }
}
