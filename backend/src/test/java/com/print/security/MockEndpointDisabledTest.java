package com.print.security;

import com.print.config.JwtUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 安全红线：{@code /payments/mock/confirm} 仅在显式开启 {@code print.payment.mock-enabled} 时注册。
 *
 * <p>当配置为真实渠道（wechat / alipay）且未开启 mock 端点时，即使携带合法 JWT，
 * 该端点也必须**不可达**（404），防止生产环境被伪造支付。
 * 对应设计文档 §7.5.5 与测试用例 TC-SEC-07。
 */
@SpringBootTest(properties = {
        "print.payment.gateway=wechat",
        "print.payment.mock-enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("demo")
class MockEndpointDisabledTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtUtils jwtUtils;

    @Test
    @DisplayName("未开启 mock 端点时 /payments/mock/confirm 返回 404（端点未注册）")
    void mockConfirmShouldBeUnavailableWhenGatewayIsNotMock() throws Exception {
        String token = jwtUtils.generate(1L, "13800000000", "ADMIN");
        mockMvc.perform(post("/api/v1/payments/mock/confirm")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderNo\":\"P0001\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("匿名访问 mock 端点被安全拦截（401），不暴露任何支付能力")
    void mockConfirmAnonymousBlocked() throws Exception {
        mockMvc.perform(post("/api/v1/payments/mock/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderNo\":\"P0001\"}"))
                .andExpect(status().isUnauthorized());
    }
}
