package com.print.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.print.common.PageResult;
import com.print.common.Result;
import com.print.order.OrderService;
import com.print.order.entity.PrintOrder;
import com.print.order.mapper.PrintOrderMapper;
import com.print.pricing.entity.PriceRule;
import com.print.pricing.mapper.PriceRuleMapper;
import com.print.task.TerminalService;
import com.print.task.entity.Terminal;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理端接口（CONTRACT.md §3.7）：计价规则、订单、终端。
 *
 * <p>骨架阶段沿用用户 JWT（不区分角色）；生产应加角色校验。
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminController {

    private final PriceRuleMapper ruleMapper;
    private final PrintOrderMapper orderMapper;
    private final OrderService orderService;
    private final TerminalService terminalService;

    public record RuleReq(String name, String paperSize, BigDecimal colorPrice, BigDecimal monoPrice,
                          BigDecimal duplexDiscount, BigDecimal minCharge, Integer status) {
    }

    public record TerminalReq(String code, String name, String agentToken) {
    }

    // ===== 计价规则 =====

    @GetMapping("/price-rules")
    public Result<List<PriceRule>> listRules() {
        return Result.ok(ruleMapper.selectList(new LambdaQueryWrapper<PriceRule>().orderByAsc(PriceRule::getId)));
    }

    @PostMapping("/price-rules")
    public Result<PriceRule> createRule(@RequestBody RuleReq req) {
        LocalDateTime now = LocalDateTime.now();
        PriceRule r = new PriceRule();
        r.setMerchantId(1L);
        r.setName(req.name());
        r.setPaperSize(req.paperSize());
        r.setColorPrice(req.colorPrice());
        r.setMonoPrice(req.monoPrice());
        r.setDuplexDiscount(req.duplexDiscount() == null ? BigDecimal.ONE : req.duplexDiscount());
        r.setMinCharge(req.minCharge() == null ? BigDecimal.ZERO : req.minCharge());
        r.setStatus(req.status() == null ? 1 : req.status());
        r.setCreatedAt(now);
        r.setUpdatedAt(now);
        ruleMapper.insert(r);
        return Result.ok(r);
    }

    @PutMapping("/price-rules/{id}")
    public Result<PriceRule> updateRule(@PathVariable Long id, @RequestBody RuleReq req) {
        PriceRule r = ruleMapper.selectById(id);
        if (r == null) {
            return Result.fail(404, "规则不存在");
        }
        if (req.name() != null) {
            r.setName(req.name());
        }
        if (req.paperSize() != null) {
            r.setPaperSize(req.paperSize());
        }
        if (req.colorPrice() != null) {
            r.setColorPrice(req.colorPrice());
        }
        if (req.monoPrice() != null) {
            r.setMonoPrice(req.monoPrice());
        }
        if (req.duplexDiscount() != null) {
            r.setDuplexDiscount(req.duplexDiscount());
        }
        if (req.minCharge() != null) {
            r.setMinCharge(req.minCharge());
        }
        if (req.status() != null) {
            r.setStatus(req.status());
        }
        r.setUpdatedAt(LocalDateTime.now());
        ruleMapper.updateById(r);
        return Result.ok(r);
    }

    // ===== 订单 =====

    @GetMapping("/orders")
    public Result<PageResult<OrderService.OrderVO>> orders(@RequestParam(defaultValue = "1") long page,
                                                           @RequestParam(defaultValue = "10") long size) {
        Page<PrintOrder> p = orderMapper.selectPage(new Page<>(page, size),
                new LambdaQueryWrapper<PrintOrder>().orderByDesc(PrintOrder::getId));
        List<OrderService.OrderVO> list = p.getRecords().stream().map(orderService::toVO).toList();
        return Result.ok(PageResult.of(list, p.getTotal(), page, size));
    }

    // ===== 终端 =====

    @GetMapping("/terminals")
    public Result<List<TerminalService.TerminalVO>> terminals() {
        return Result.ok(terminalService.list());
    }

    @PostMapping("/terminals")
    public Result<Terminal> createTerminal(@RequestBody TerminalReq req) {
        return Result.ok(terminalService.create(req.code(), req.name(), req.agentToken()));
    }
}
