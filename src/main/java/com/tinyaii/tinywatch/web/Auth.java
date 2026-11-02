package com.tinyaii.tinywatch.web;

import java.security.SecureRandom;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录、会话与失败限流。
 *
 * <p>密码在 config.yml 里只存 PBKDF2 哈希（见 {@link Password}），登录时现算现比。
 * 服务器重启后所有 token 失效（存在内存里），这是有意的 —— 不想把会话写到磁盘上。
 *
 * <p><b>只读分享</b>：给别的管理员看数据用，链接里带一个 readOnly 的 token。
 * 它和正常会话走同一套鉴权，只是所有「会改东西」的接口都会拒掉（403）。
 * 为什么不在前端藏按钮了事：前端藏起来只是好看，接口才是真正的大门。
 *
 * <p><b>为什么要限流</b>：哈希把每次尝试的成本抬到几十毫秒，但网络快的人照样能刷。
 * 这里再按来源 IP 计数，连续失败 5 次锁 5 分钟 —— 两道加起来，字典攻击基本没戏。
 */
public final class Auth {

    /** 连续失败几次上锁。 */
    private static final int MAX_FAILS = 5;
    /** 锁多久（毫秒）。 */
    private static final long LOCK_MS = 5 * 60 * 1000L;
    /** 失败记录最多留这么多 IP，超了清一遍过期的。 */
    private static final int MAX_TRACKED = 2000;

    /** 一条会话。 */
    private static final class Session {
        final long expires;
        final boolean readOnly;

        Session(long expires, boolean readOnly) {
            this.expires = expires;
            this.readOnly = readOnly;
        }
    }

    /** 某个 IP 的失败记录。 */
    private static final class Fails {
        int count;
        long lockUntil;
        long last;
    }

    private final long ttlMillis;
    private final String passwordHash;

    private final Map<String, Session> sessions = new ConcurrentHashMap<String, Session>();
    private final Map<String, Fails> fails = new ConcurrentHashMap<String, Fails>();
    private final SecureRandom random = new SecureRandom();

    public Auth(String passwordHash, int sessionHours) {
        this.passwordHash = passwordHash == null ? "" : passwordHash.trim();
        this.ttlMillis = Math.max(1, sessionHours) * 3600_000L;
    }

    public boolean hasPassword() {
        return Password.isHashed(passwordHash);
    }

    /**
     * 校验密码。成功返回新 token，失败返回 null。
     *
     * @param ip 来源 IP，用于失败限流
     */
    public String login(String given, String ip) {
        if (!hasPassword() || given == null) {
            return null;
        }
        String key = ip == null ? "?" : ip;
        if (lockedFor(key) > 0) {
            return null;
        }
        if (!Password.verify(given, passwordHash)) {
            recordFail(key);
            return null;
        }
        fails.remove(key);
        String token = newToken();
        sessions.put(token, new Session(System.currentTimeMillis() + ttlMillis, false));
        sweep();
        return token;
    }

    /** 这个 IP 还要锁多久（毫秒），0 表示没锁。 */
    public long lockedFor(String ip) {
        Fails f = fails.get(ip == null ? "?" : ip);
        if (f == null) {
            return 0L;
        }
        long left = f.lockUntil - System.currentTimeMillis();
        return left > 0 ? left : 0L;
    }

    private void recordFail(String ip) {
        long now = System.currentTimeMillis();
        Fails f = fails.get(ip);
        if (f == null) {
            f = new Fails();
            fails.put(ip, f);
        }
        f.count++;
        f.last = now;
        if (f.count >= MAX_FAILS) {
            f.lockUntil = now + LOCK_MS;
            f.count = 0;
        }
        if (fails.size() > MAX_TRACKED) {
            sweepFails(now);
        }
    }

    private void sweepFails(long now) {
        Iterator<Map.Entry<String, Fails>> it = fails.entrySet().iterator();
        while (it.hasNext()) {
            Fails f = it.next().getValue();
            // 锁已过期、且一小时没动静的记录可以丢了
            if (f.lockUntil < now && now - f.last > 3600_000L) {
                it.remove();
            }
        }
    }

    /**
     * 生成一个只读分享 token。
     *
     * @param minutes 有效期（分钟），1~10080
     */
    public String createShare(int minutes) {
        int m = Math.max(1, Math.min(7 * 24 * 60, minutes));
        String token = newToken();
        sessions.put(token, new Session(System.currentTimeMillis() + m * 60_000L, true));
        sweep();
        return token;
    }

    /** token 是否有效。过期的顺手清掉。 */
    public boolean valid(String token) {
        return session(token) != null;
    }

    /** 这个 token 是不是只读的（无效 token 返回 false）。 */
    public boolean isReadOnly(String token) {
        Session s = session(token);
        return s != null && s.readOnly;
    }

    private Session session(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        Session s = sessions.get(token);
        if (s == null) {
            return null;
        }
        if (s.expires < System.currentTimeMillis()) {
            sessions.remove(token);
            return null;
        }
        return s;
    }

    public void logout(String token) {
        if (token != null) {
            sessions.remove(token);
        }
    }

    public int sessionCount() {
        return sessions.size();
    }

    private void sweep() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Session>> it = sessions.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().expires < now) {
                it.remove();
            }
        }
    }

    private String newToken() {
        byte[] buf = new byte[24];
        random.nextBytes(buf);
        StringBuilder sb = new StringBuilder(buf.length * 2);
        for (byte b : buf) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
