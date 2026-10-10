package com.print.pricing;

import com.print.common.BizException;
import com.print.common.ResultCode;
import com.print.file.FileService;
import com.print.file.entity.PrintFile;
import com.print.pricing.entity.PriceRule;
import com.print.pricing.mapper.PriceRuleMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 计价核心纯单元测试（不启 Spring，Mock 掉 Mapper 与文件服务）。
 *
 * <p>对应测试策略 TC-PRICE-01 ~ 14。基线规则：A4 黑白 0.20 / 彩色 0.80、
 * 双面折扣 0.90、起印价 1.00；算法类用例将起印价置 0 以便断言纯单价。
 */
@ExtendWith(MockitoExtension.class)
class PricingServiceTest {

    private static final long USER_ID = 7L;
    private static final long FILE_ID = 1L;

    @Mock
    private PriceRuleMapper ruleMapper;
    @Mock
    private FileService fileService;

    private PricingService service() {
        return new PricingService(ruleMapper, fileService);
    }

    private void stubFile(int pageCount) {
        PrintFile f = new PrintFile();
        f.setId(FILE_ID);
        f.setUserId(USER_ID);
        f.setPageCount(pageCount);
        when(fileService.requireOwned(FILE_ID, USER_ID)).thenReturn(f);
    }

    private void stubRule(String paperSize, String colorPrice, String monoPrice,
                          String duplexDiscount, String minCharge) {
        PriceRule r = new PriceRule();
        r.setId(1L);
        r.setPaperSize(paperSize);
        r.setColorPrice(new BigDecimal(colorPrice));
        r.setMonoPrice(new BigDecimal(monoPrice));
        r.setDuplexDiscount(new BigDecimal(duplexDiscount));
        r.setMinCharge(new BigDecimal(minCharge));
        when(ruleMapper.selectOne(any())).thenReturn(r);
    }

    private PricingService.QuoteResult quote(int copies, String color, String duplex,
                                             String paper, PricingService.PageRange range) {
        return service().quote(new PricingService.QuoteReq(FILE_ID, copies, color, duplex, paper, range), USER_ID);
    }

    @Test
    @DisplayName("TC-PRICE-01 黑白单面 A4：5 页 × 1 份 = 1.00")
    void monoSimplexA4() {
        stubFile(5);
        stubRule("A4", "0.80", "0.20", "0.90", "0.00");
        var r = quote(1, "MONO", "SIMPLEX", "A4", null);
        assertThat(r.billablePages()).isEqualTo(5);
        assertThat(r.unitPrice()).isEqualByComparingTo("0.20");
        assertThat(r.duplexDiscount()).isEqualByComparingTo("1.00");
        assertThat(r.amount()).isEqualByComparingTo("1.00");
    }

    @Test
    @DisplayName("TC-PRICE-02 彩色单面 A4：5 页 × 1 份 = 4.00")
    void colorSimplexA4() {
        stubFile(5);
        stubRule("A4", "0.80", "0.20", "0.90", "0.00");
        var r = quote(1, "COLOR", "SIMPLEX", "A4", null);
        assertThat(r.unitPrice()).isEqualByComparingTo("0.80");
        assertThat(r.amount()).isEqualByComparingTo("4.00");
    }

    @Test
    @DisplayName("TC-PRICE-03 黑白双面 A4：5 页 × 0.20 × 0.9 = 0.90")
    void monoDuplexDiscount() {
        stubFile(5);
        stubRule("A4", "0.80", "0.20", "0.90", "0.00");
        var r = quote(1, "MONO", "DUPLEX", "A4", null);
        assertThat(r.duplexDiscount()).isEqualByComparingTo("0.90");
        assertThat(r.amount()).isEqualByComparingTo("0.90");
    }

    @Test
    @DisplayName("TC-PRICE-04 多份：3 页 × 4 份 黑白 = 2.40")
    void copiesMultiply() {
        stubFile(3);
        stubRule("A4", "0.80", "0.20", "0.90", "0.00");
        var r = quote(4, "MONO", "SIMPLEX", "A4", null);
        assertThat(r.amount()).isEqualByComparingTo("2.40");
    }

    @Test
    @DisplayName("TC-PRICE-05 A3 黑白：2 页 × 0.40 = 0.80")
    void a3Mono() {
        stubFile(2);
        stubRule("A3", "1.60", "0.40", "0.90", "0.00");
        var r = quote(1, "MONO", "SIMPLEX", "A3", null);
        assertThat(r.unitPrice()).isEqualByComparingTo("0.40");
        assertThat(r.amount()).isEqualByComparingTo("0.80");
    }

    @Test
    @DisplayName("TC-PRICE-06 起印价生效：1 页 0.20 < 起印价 1.00 -> 1.00")
    void minChargeApplied() {
        stubFile(1);
        stubRule("A4", "0.80", "0.20", "0.90", "1.00");
        var r = quote(1, "MONO", "SIMPLEX", "A4", null);
        assertThat(r.amount()).isEqualByComparingTo("1.00");
    }

    @Test
    @DisplayName("TC-PRICE-07 边界 0 页：按起印价 1.00（锁定行为）")
    void zeroPagesUsesMinCharge() {
        stubFile(0);
        stubRule("A4", "0.80", "0.20", "0.90", "1.00");
        var r = quote(1, "MONO", "SIMPLEX", "A4", null);
        assertThat(r.pageCount()).isZero();
        assertThat(r.billablePages()).isZero();
        assertThat(r.amount()).isEqualByComparingTo("1.00");
    }

    @Test
    @DisplayName("TC-PRICE-08 页码范围：10 页文件选 3~5 页 -> 计费 3 页")
    void pageRange() {
        stubFile(10);
        stubRule("A4", "0.80", "0.20", "0.90", "0.00");
        var r = quote(1, "MONO", "SIMPLEX", "A4", new PricingService.PageRange(3, 5));
        assertThat(r.billablePages()).isEqualTo(3);
        assertThat(r.amount()).isEqualByComparingTo("0.60");
    }

    @Test
    @DisplayName("TC-PRICE-09 页码越界：8~15 超出 10 页 -> 3002")
    void pageRangeExceed() {
        stubFile(10);
        stubRule("A4", "0.80", "0.20", "0.90", "0.00");
        BizException ex = catchThrowableOfType(
                () -> quote(1, "MONO", "SIMPLEX", "A4", new PricingService.PageRange(8, 15)),
                BizException.class);
        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo(ResultCode.PAGE_RANGE_INVALID.code());
    }

    @Test
    @DisplayName("TC-PRICE-10 起止倒置：5~3 -> 参数校验失败 400")
    void pageRangeInverted() {
        stubFile(10);
        stubRule("A4", "0.80", "0.20", "0.90", "0.00");
        BizException ex = catchThrowableOfType(
                () -> quote(1, "MONO", "SIMPLEX", "A4", new PricingService.PageRange(5, 3)),
                BizException.class);
        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo(ResultCode.PARAM_ERROR.code());
    }

    @Test
    @DisplayName("TC-PRICE-11 双面奇数页：5 页双面仍按 5 页计费 × 0.9")
    void duplexOddPages() {
        stubFile(5);
        stubRule("A4", "0.80", "0.20", "0.90", "0.00");
        var r = quote(1, "MONO", "DUPLEX", "A4", null);
        assertThat(r.billablePages()).isEqualTo(5);
        assertThat(r.amount()).isEqualByComparingTo("0.90");
    }

    @Test
    @DisplayName("TC-PRICE-13 无匹配规则 -> 3001")
    void noRuleThrows() {
        stubFile(5);
        when(ruleMapper.selectOne(any())).thenReturn(null);
        BizException ex = catchThrowableOfType(
                () -> quote(1, "MONO", "SIMPLEX", "A4", null), BizException.class);
        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo(ResultCode.NO_PRICING_RULE.code());
    }

    @Test
    @DisplayName("TC-PRICE-14 金额精度：0.20 × 3 份 × 0.9 = 0.54（BigDecimal 两位无误差）")
    void amountPrecision() {
        stubFile(1);
        stubRule("A4", "0.80", "0.20", "0.90", "0.00");
        var r = quote(3, "MONO", "DUPLEX", "A4", null);
        assertThat(r.amount().toPlainString()).isEqualTo("0.54");
        assertThat(r.amount().scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("份数非法（0 份）-> 参数校验失败 400")
    void invalidCopies() {
        BizException ex = catchThrowableOfType(
                () -> quote(0, "MONO", "SIMPLEX", "A4", null), BizException.class);
        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo(ResultCode.PARAM_ERROR.code());
    }
}
