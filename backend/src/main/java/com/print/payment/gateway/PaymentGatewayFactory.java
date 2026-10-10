package com.print.payment.gateway;

import com.print.common.BizException;
import com.print.common.ResultCode;
import com.print.config.PrintProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 支付网关工厂：按 gatewayCode 路由到具体实现（可插拔核心）。
 */
@Component
@RequiredArgsConstructor
public class PaymentGatewayFactory {

    private final List<PaymentGateway> gateways;
    private final PrintProperties props;

    public PaymentGateway get(String gatewayCode) {
        return gateways.stream()
                .filter(g -> g.getGatewayCode().equalsIgnoreCase(gatewayCode))
                .findFirst()
                .orElseThrow(() -> new BizException(ResultCode.PAYMENT_GATEWAY_ERROR.code(),
                        "未找到支付网关实现: " + gatewayCode));
    }

    /** 当前配置的网关。 */
    public PaymentGateway current() {
        return get(props.getPayment().getGateway());
    }

    public boolean isMock() {
        return "mock".equalsIgnoreCase(props.getPayment().getGateway());
    }
}
