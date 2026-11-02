package com.tinyaii.tinywatch.util;

import org.bukkit.entity.Player;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 取玩家延迟。
 *
 * <p><b>为什么要反射</b>：{@code Player#getPing()} 是 1.16.4 才加进 API 的。
 * 本插件声明支持 1.13，直接写 {@code player.getPing()} 用 1.13 的 spigot-api 编译时
 * 根本找不到这个方法。
 *
 * <p>两条路：
 * <ol>
 *   <li>新版本：反射调 {@code Player#getPing()}（存在就用）</li>
 *   <li>老版本：反射 CraftPlayer#getHandle() 拿服务端实体，读它的 {@code ping} 字段</li>
 * </ol>
 *
 * <p>都失败返回 -1，界面显示「—」。监控面板最忌讳把「不知道」显示成「0 延迟」。
 */
public final class Pings {

    private static final Method API_GET_PING;

    /** 找到的 ping 字段，按 handle 类缓存（每个服务端实现只会命中一次）。 */
    private static volatile Field cachedField;
    private static volatile Class<?> cachedFor;
    private static volatile boolean lookupFailed;

    static {
        Method m = null;
        try {
            m = Player.class.getMethod("getPing");
        } catch (Throwable ignored) {
            // 1.16.4 以下没有，正常
        }
        API_GET_PING = m;
    }

    private Pings() {
    }

    public static int of(Player p) {
        if (p == null) {
            return -1;
        }
        if (API_GET_PING != null) {
            try {
                Object v = API_GET_PING.invoke(p);
                if (v instanceof Number) {
                    return ((Number) v).intValue();
                }
            } catch (Throwable ignored) {
                // 掉下去走反射字段
            }
        }
        return fromHandle(p);
    }

    private static int fromHandle(Player p) {
        if (lookupFailed) {
            return -1;
        }
        try {
            Object handle = p.getClass().getMethod("getHandle").invoke(p);
            if (handle == null) {
                return -1;
            }
            Field f = pingField(handle.getClass());
            if (f == null) {
                return -1;
            }
            Object v = f.get(handle);
            return v instanceof Number ? ((Number) v).intValue() : -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /**
     * 在 handle 的类及父类上找 {@code ping} 字段。
     *
     * <p>必须顺着父类找：1.13~1.16 里这个字段定义在 EntityPlayer 上，
     * 而 getHandle() 实际返回的类型在不同版本层级不同。
     */
    private static Field pingField(Class<?> handleClass) {
        Field f = cachedField;
        if (f != null && cachedFor == handleClass) {
            return f;
        }
        synchronized (Pings.class) {
            if (cachedField != null && cachedFor == handleClass) {
                return cachedField;
            }
            Class<?> c = handleClass;
            while (c != null && c != Object.class) {
                try {
                    Field found = c.getDeclaredField("ping");
                    found.setAccessible(true);
                    cachedField = found;
                    cachedFor = handleClass;
                    return found;
                } catch (NoSuchFieldException ignored) {
                    c = c.getSuperclass();
                } catch (Throwable ignored) {
                    break;
                }
            }
            lookupFailed = true;
            return null;
        }
    }
}
