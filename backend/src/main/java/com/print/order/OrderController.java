package com.print.order;

import com.print.common.PageResult;
import com.print.common.Result;
import com.print.config.UserContext;
import com.print.pricing.PricingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 订单接口（CONTRACT.md §3.4）。
 */
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    public record CreateOrderReq(
            @NotNull(message = "fileId 不能为空") Long fileId,
            @NotNull(message = "copies 不能为空") Integer copies,
            String colorMode,
            String duplexMode,
            String paperSize,
            PricingService.PageRange pageRange,
            String terminalCode) {
    }

    @PostMapping
    public Result<OrderService.OrderVO> create(@Valid @RequestBody CreateOrderReq req) {
        PricingService.QuoteReq quoteReq = new PricingService.QuoteReq(
                req.fileId(), req.copies(),
                nvl(req.colorMode(), "MONO"),
                nvl(req.duplexMode(), "SIMPLEX"),
                nvl(req.paperSize(), "A4"),
                req.pageRange());
        return Result.ok(orderService.create(quoteReq, req.terminalCode(), UserContext.userId()));
    }

    @GetMapping("/{orderNo}")
    public Result<OrderService.OrderVO> detail(@PathVariable String orderNo) {
        return Result.ok(orderService.detail(orderNo, UserContext.userId()));
    }

    @GetMapping
    public Result<PageResult<OrderService.OrderVO>> list(@RequestParam(defaultValue = "1") long page,
                                                         @RequestParam(defaultValue = "10") long size) {
        return Result.ok(orderService.listByUser(UserContext.userId(), page, size));
    }

    @PostMapping("/{orderNo}/cancel")
    public Result<Void> cancel(@PathVariable String orderNo) {
        orderService.cancel(orderNo, UserContext.userId());
        return Result.ok();
    }

    private static String nvl(String v, String def) {
        return (v == null || v.isBlank()) ? def : v;
    }
}
