package com.print.pricing;

import com.print.common.Result;
import com.print.config.UserContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 计价接口（CONTRACT.md §3.3）。
 */
@RestController
@RequestMapping("/api/v1/pricing")
@RequiredArgsConstructor
public class PricingController {

    private final PricingService pricingService;

    public record QuoteReqBody(
            @NotNull(message = "fileId 不能为空") Long fileId,
            @NotNull(message = "copies 不能为空") Integer copies,
            String colorMode,
            String duplexMode,
            String paperSize,
            PricingService.PageRange pageRange) {
    }

    @PostMapping("/quote")
    public Result<PricingService.QuoteResult> quote(@Valid @RequestBody QuoteReqBody body) {
        PricingService.QuoteReq req = new PricingService.QuoteReq(
                body.fileId(), body.copies(),
                body.colorMode() == null ? "MONO" : body.colorMode(),
                body.duplexMode() == null ? "SIMPLEX" : body.duplexMode(),
                body.paperSize() == null ? "A4" : body.paperSize(),
                body.pageRange());
        return Result.ok(pricingService.quote(req, UserContext.userId()));
    }
}
