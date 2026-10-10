package com.print.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.print.common.BizException;
import com.print.common.ResultCode;
import com.print.config.PrintProperties;
import com.print.task.entity.Terminal;
import com.print.task.mapper.TerminalMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 终端服务：终端解析、助手鉴权、心跳与在线状态维护。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TerminalService {

    private final TerminalMapper terminalMapper;
    private final PrintProperties props;

    public record TerminalVO(Long id, String code, String name, Integer onlineStatus,
                             String onlineText, LocalDateTime lastHeartbeat, String printersJson) {
    }

    /** 按终端编码解析终端 ID；为空则取第一个终端（单店场景兜底）。 */
    public Long resolveTerminalId(String code) {
        Terminal t = null;
        if (code != null && !code.isBlank()) {
            t = terminalMapper.selectOne(new LambdaQueryWrapper<Terminal>().eq(Terminal::getCode, code));
        }
        if (t == null) {
            t = terminalMapper.selectOne(new LambdaQueryWrapper<Terminal>()
                    .orderByAsc(Terminal::getId).last("LIMIT 1"));
        }
        return t == null ? null : t.getId();
    }

    /** 助手鉴权：校验 X-Agent-Token。 */
    public Terminal requireByToken(String token) {
        if (token == null || token.isBlank()) {
            throw new BizException(ResultCode.UNAUTHORIZED);
        }
        Terminal t = terminalMapper.selectOne(new LambdaQueryWrapper<Terminal>()
                .eq(Terminal::getAgentToken, token));
        if (t == null) {
            throw new BizException(ResultCode.UNAUTHORIZED.code(), "助手令牌无效");
        }
        return t;
    }

    public Terminal requireByCode(String code) {
        Terminal t = terminalMapper.selectOne(new LambdaQueryWrapper<Terminal>().eq(Terminal::getCode, code));
        if (t == null) {
            throw new BizException(ResultCode.NOT_FOUND.code(), "终端不存在: " + code);
        }
        return t;
    }

    /** 心跳：刷新在线状态与打印机列表。 */
    public void heartbeat(Terminal t, String printersJson) {
        LocalDateTime now = LocalDateTime.now();
        terminalMapper.update(null, new LambdaUpdateWrapper<Terminal>()
                .eq(Terminal::getId, t.getId())
                .set(Terminal::getOnlineStatus, 1)
                .set(Terminal::getLastHeartbeat, now)
                .set(Terminal::getPrintersJson, printersJson)
                .set(Terminal::getUpdatedAt, now));
    }

    /** 扫描并标记离线终端。 */
    public int markOffline() {
        LocalDateTime threshold = LocalDateTime.now().minusSeconds(props.getAgent().getOfflineSeconds());
        int rows = terminalMapper.update(null, new LambdaUpdateWrapper<Terminal>()
                .eq(Terminal::getOnlineStatus, 1)
                .lt(Terminal::getLastHeartbeat, threshold)
                .set(Terminal::getOnlineStatus, 0)
                .set(Terminal::getUpdatedAt, LocalDateTime.now()));
        if (rows > 0) {
            log.warn("检测到 {} 个终端离线", rows);
        }
        return rows;
    }

    public List<TerminalVO> list() {
        return terminalMapper.selectList(new LambdaQueryWrapper<Terminal>().orderByAsc(Terminal::getId))
                .stream()
                .map(t -> new TerminalVO(t.getId(), t.getCode(), t.getName(), t.getOnlineStatus(),
                        t.getOnlineStatus() != null && t.getOnlineStatus() == 1 ? "在线" : "离线",
                        t.getLastHeartbeat(), t.getPrintersJson()))
                .toList();
    }

    public Terminal create(String code, String name, String agentToken) {
        LocalDateTime now = LocalDateTime.now();
        Terminal t = new Terminal();
        t.setMerchantId(1L);
        t.setCode(code);
        t.setName(name);
        t.setAgentToken(agentToken);
        t.setOnlineStatus(0);
        t.setCreatedAt(now);
        t.setUpdatedAt(now);
        terminalMapper.insert(t);
        return t;
    }
}
