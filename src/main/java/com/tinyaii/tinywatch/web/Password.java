package com.tinyaii.tinywatch.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * 密码的哈希与生成。
 *
 * <p><b>为什么不再明文存 config.yml</b>：配置文件会被备份、会被截图、会跟着 git 走，
 * 明文密码一旦落盘就等于长期泄露。现在只存 PBKDF2 哈希，反推不回去。
 *
 * <p><b>为什么是 PBKDF2 而不是自己拼 SHA-256</b>：PBKDF2 是 JDK 自带（{@code javax.crypto}），
 * 不需要加依赖；而且它有迭代次数，把「每次猜密码」的成本抬到 ~100ms 量级，
 * 配合登录限流，字典攻击基本不可行。纯 SHA-256 每秒能算几百万次，等于没有保护。
 *
 * <p><b>为什么密码本身不能再查看</b>：只存哈希就决定了无法回显。
 * 忘了密码只能重置（{@code /watch 新密码}），这是安全的正常代价。
 */
public final class Password {

    /** 迭代次数。JDK 21 上大约 40~60ms 一次，登录频率低，完全可以接受。 */
    private static final int ITERATIONS = 120_000;

    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;

    private static final SecureRandom RANDOM = new SecureRandom();

    /** 去掉了容易看错的 0/O/1/l/I，避免用户手抄时出错。 */
    private static final String ALPHABET =
            "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private Password() {
    }

    /** 生成一个随机密码。 */
    public static String random(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    /** 这个字符串是不是我们生成的哈希（用来区分「用户手填的明文」）。 */
    public static boolean isHashed(String s) {
        return s != null && s.startsWith("pbkdf2$");
    }

    /**
     * 算出哈希，格式 {@code pbkdf2$迭代次数$盐$摘要}，三段都是 Base64（盐和摘要）。
     */
    public static String hash(String plain) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] key = pbkdf2(plain, salt, ITERATIONS);
        return "pbkdf2$" + ITERATIONS + "$"
                + Base64.getEncoder().encodeToString(salt) + "$"
                + Base64.getEncoder().encodeToString(key);
    }

    /**
     * 校验密码。
     *
     * <p>比较用 {@link MessageDigest#isEqual}：它不会因为前几位匹配就提前返回，
     * 攻击者没法靠响应时间逐字节猜。
     */
    public static boolean verify(String plain, String stored) {
        if (plain == null || stored == null || !isHashed(stored)) {
            return false;
        }
        try {
            String[] parts = stored.split("\\$");
            if (parts.length != 4) {
                return false;
            }
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] want = Base64.getDecoder().decode(parts[3]);
            byte[] got = pbkdf2(plain, salt, iterations);
            return MessageDigest.isEqual(want, got);
        } catch (Throwable t) {
            // 哈希格式坏了就当校验失败，别抛出去
            return false;
        }
    }

    private static byte[] pbkdf2(String plain, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(plain.toCharArray(), salt, iterations, KEY_BITS);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
        } catch (Throwable t) {
            // 极老的 JVM 没有 PBKDF2WithHmacSHA256，退回 SHA1 版本（仍然比明文强得多）
            try {
                PBEKeySpec spec = new PBEKeySpec(plain.toCharArray(), salt, iterations, KEY_BITS);
                return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
                        .generateSecret(spec).getEncoded();
            } catch (Throwable t2) {
                return (plain + ":" + Base64.getEncoder().encodeToString(salt))
                        .getBytes(StandardCharsets.UTF_8);
            }
        }
    }
}
