package com.print.auth.store;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Redis 实现（prod 档）：验证码与冷却时间均带 TTL。
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "print.queue.type", havingValue = "redis")
public class RedisSmsCodeStore implements SmsCodeStore {

    private static final String CODE_KEY = "sms:code:";
    private static final String COOLDOWN_KEY = "sms:cooldown:";

    private final StringRedisTemplate redis;

    @Override
    public void saveCode(String phone, String code, int ttlSeconds) {
        redis.opsForValue().set(CODE_KEY + phone, code, Duration.ofSeconds(ttlSeconds));
    }

    @Override
    public String getCode(String phone) {
        return redis.opsForValue().get(CODE_KEY + phone);
    }

    @Override
    public void removeCode(String phone) {
        redis.delete(CODE_KEY + phone);
    }

    @Override
    public boolean inCooldown(String phone) {
        return Boolean.TRUE.equals(redis.hasKey(COOLDOWN_KEY + phone));
    }

    @Override
    public void markCooldown(String phone, int seconds) {
        redis.opsForValue().set(COOLDOWN_KEY + phone, "1", Duration.ofSeconds(seconds));
    }
}
