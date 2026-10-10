package com.print.auth.store;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存实现（demo 档）。单机内存存验证码与发送冷却时间。
 */
@Component
@ConditionalOnProperty(name = "print.queue.type", havingValue = "memory", matchIfMissing = true)
public class InMemorySmsCodeStore implements SmsCodeStore {

    private record Entry(String code, long expireAt) {
    }

    private final Map<String, Entry> codes = new ConcurrentHashMap<>();
    private final Map<String, Long> cooldown = new ConcurrentHashMap<>();

    @Override
    public void saveCode(String phone, String code, int ttlSeconds) {
        codes.put(phone, new Entry(code, Instant.now().getEpochSecond() + ttlSeconds));
    }

    @Override
    public String getCode(String phone) {
        Entry e = codes.get(phone);
        if (e == null) {
            return null;
        }
        if (e.expireAt() < Instant.now().getEpochSecond()) {
            codes.remove(phone);
            return null;
        }
        return e.code();
    }

    @Override
    public void removeCode(String phone) {
        codes.remove(phone);
    }

    @Override
    public boolean inCooldown(String phone) {
        Long until = cooldown.get(phone);
        return until != null && until > Instant.now().getEpochSecond();
    }

    @Override
    public void markCooldown(String phone, int seconds) {
        cooldown.put(phone, Instant.now().getEpochSecond() + seconds);
    }
}
