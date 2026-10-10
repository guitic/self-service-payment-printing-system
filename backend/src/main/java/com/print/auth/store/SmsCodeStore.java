package com.print.auth.store;

/**
 * 短信验证码存储抽象：demo 档用内存实现，prod 档用 Redis 实现。
 */
public interface SmsCodeStore {

    void saveCode(String phone, String code, int ttlSeconds);

    String getCode(String phone);

    void removeCode(String phone);

    boolean inCooldown(String phone);

    void markCooldown(String phone, int seconds);
}
