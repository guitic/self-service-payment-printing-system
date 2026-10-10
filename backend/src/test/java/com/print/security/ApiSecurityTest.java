package com.print.security;

import com.print.config.JwtUtils;
import com.print.file.entity.PrintFile;
import com.print.file.mapper.PrintFileMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.concurrent.ThreadLocalRandom;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * API 安全用例（对齐设计文档 §7.6.5 与 TC-SEC-* 系列）：
 * 未鉴权拦截、越权访问、助手令牌校验、验证码限频。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("demo")
class ApiSecurityTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtUtils jwtUtils;
    @Autowired
    private PrintFileMapper fileMapper;

    @Test
    @DisplayName("TC-SEC-02 无 Token 访问受保护接口被拦截（HTTP 401）")
    void protectedEndpointWithoutToken() throws Exception {
        mockMvc.perform(get("/api/v1/orders"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("TC-SEC-02 伪造 Token 访问受保护接口被拦截（HTTP 401）")
    void protectedEndpointWithForgedToken() throws Exception {
        mockMvc.perform(get("/api/v1/orders")
                        .header("Authorization", "Bearer not-a-real-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("TC-SEC-01 用户 B 访问用户 A 的文件元数据被拒（403）")
    void crossUserFileAccessDenied() throws Exception {
        PrintFile file = newFileOwnedBy(999_999L);
        String tokenOfOtherUser = jwtUtils.generate(1000L, "13800001000");

        mockMvc.perform(get("/api/v1/files/" + file.getId())
                        .header("Authorization", "Bearer " + tokenOfOtherUser))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    @DisplayName("TC-SEC-01 用户 B 下载用户 A 的文件被拒（403）")
    void crossUserFileDownloadDenied() throws Exception {
        PrintFile file = newFileOwnedBy(999_999L);
        String tokenOfOtherUser = jwtUtils.generate(1000L, "13800001001");

        mockMvc.perform(get("/api/v1/files/" + file.getId() + "/download")
                        .header("Authorization", "Bearer " + tokenOfOtherUser))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    @DisplayName("TC-SEC-06 助手使用错误 X-Agent-Token 被拒（401）")
    void agentHeartbeatWithWrongToken() throws Exception {
        mockMvc.perform(post("/api/v1/agent/heartbeat")
                        .header("X-Agent-Token", "wrong-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"terminalCode\":\"TERM-001\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("TC-AUTH-06 同号 60s 内重复发送验证码被限频（code=1003）")
    void smsSendRateLimited() throws Exception {
        String phone = "139" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
        String body = "{\"phone\":\"" + phone + "\"}";

        mockMvc.perform(post("/api/v1/auth/sms/send")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(post("/api/v1/auth/sms/send")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1003));
    }

    @Test
    @DisplayName("TC-AUTH-07 手机号格式非法被参数校验拦截（code=400）")
    void smsSendWithInvalidPhone() throws Exception {
        mockMvc.perform(post("/api/v1/auth/sms/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"abc\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    private PrintFile newFileOwnedBy(Long userId) {
        LocalDateTime now = LocalDateTime.now();
        PrintFile f = new PrintFile();
        f.setUserId(userId);
        f.setOriginalName("secret.pdf");
        f.setStoragePath("security/" + System.nanoTime() + ".pdf");
        f.setContentType("application/pdf");
        f.setSizeBytes(10L);
        f.setPageCount(1);
        f.setStatus(0);
        f.setCreatedAt(now);
        f.setUpdatedAt(now);
        fileMapper.insert(f);
        return f;
    }
}
