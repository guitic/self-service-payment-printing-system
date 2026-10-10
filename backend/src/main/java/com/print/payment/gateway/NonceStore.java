package com.print.payment.gateway;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 回调防重放缓存（nonce 去重）。
 *
 * <p>单机内存实现，带 TTL 惰性清理；多节点部署时应替换为 Redis
 * （如 {@code SET nonce NX EX 300}）以保证跨节点一致。
 */
@Component
public class NonceStore {

    private static final int CLEANUP_THRESHOLD = 10_000;

    private final Map<String, Long> seen = new ConcurrentHashMap<>();

    /**
     * 记录一次 nonce。
     *
     * @return {@code true} 表示首次出现（非重放）；{@code false} 表示在 TTL 内已出现过
     */
    public boolean firstSeen(String key, long ttlSeconds) {
        long now = System.currentTimeMillis();
        if (seen.size() > CLEANUP_THRESHOLD) {
            seen.entrySet().removeIf(e -> e.getValue() < now);
        }
        Long prev = seen.putIfAbsent(key, now + ttlSeconds * 1000);
        if (prev == null) {
            return true;
        }
        if (prev < now) {
            seen.put(key, now + ttlSeconds * 1000);
            return true;
        }
        return false;
    }
}
