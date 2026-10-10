package com.print.pricing;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.print.common.BizException;
import com.print.common.ResultCode;
import com.print.file.FileService;
import com.print.file.entity.PrintFile;
import com.print.pricing.entity.PriceRule;
import com.print.pricing.mapper.PriceRuleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 计价服务。
 *
 * <p>公式：{@code amount = billablePages × copies × unitPrice × duplexDiscount}
 * （按实际数量与单价直接计算，不设起印价/最低消费）
 * （ADR-003：下单时快照金额与参数，规则后续改动不回填历史订单）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PricingService {

    public record PageRange(Integer from, Integer to) {
    }

    public record QuoteReq(Long fileId, Integer copies, String colorMode, String duplexMode,
                           String paperSize, PageRange pageRange) {
    }

    public record QuoteResult(int pageCount, int billablePages, BigDecimal unitPrice,
                              BigDecimal duplexDiscount, BigDecimal amount, String currency, Long ruleId) {
    }

    private final PriceRuleMapper ruleMapper;
    private final FileService fileService;

    /** 试算（下单与试算共用同一算法，保证 TC-PRICE-15 一致）。 */
    public QuoteResult quote(QuoteReq req, Long userId) {
        if (req.copies() == null || req.copies() < 1) {
            throw new BizException(ResultCode.PARAM_ERROR.code(), "份数必须大于 0");
        }
        PrintFile file = fileService.requireOwned(req.fileId(), userId);
        PriceRule rule = findRule(req.paperSize());

        int totalPages = file.getPageCount() == null ? 0 : file.getPageCount();
        int billable = billablePages(totalPages, req.pageRange());

        boolean color = "COLOR".equalsIgnoreCase(req.colorMode());
        BigDecimal unitPrice = color ? rule.getColorPrice() : rule.getMonoPrice();
        BigDecimal discount = "DUPLEX".equalsIgnoreCase(req.duplexMode())
                ? rule.getDuplexDiscount() : BigDecimal.ONE;

        BigDecimal amount = unitPrice
                .multiply(BigDecimal.valueOf((long) billable * req.copies()))
                .multiply(discount)
                .setScale(2, RoundingMode.HALF_UP);

        return new QuoteResult(totalPages, billable, unitPrice, discount, amount, "CNY", rule.getId());
    }

    /** 取当前生效规则。 */
    public PriceRule findRule(String paperSize) {
        PriceRule rule = ruleMapper.selectOne(new LambdaQueryWrapper<PriceRule>()
                .eq(PriceRule::getPaperSize, paperSize)
                .eq(PriceRule::getStatus, 1)
                .orderByDesc(PriceRule::getId)
                .last("LIMIT 1"));
        if (rule == null) {
            throw new BizException(ResultCode.NO_PRICING_RULE);
        }
        return rule;
    }

    private int billablePages(int totalPages, PageRange range) {
        if (range == null || (range.from() == null && range.to() == null)) {
            return totalPages;
        }
        int from = range.from() == null ? 1 : range.from();
        int to = range.to() == null ? totalPages : range.to();
        if (from < 1 || to < from) {
            throw new BizException(ResultCode.PARAM_ERROR.code(), "页码范围不合法");
        }
        if (to > totalPages) {
            throw new BizException(ResultCode.PAGE_RANGE_INVALID);
        }
        return to - from + 1;
    }
}
