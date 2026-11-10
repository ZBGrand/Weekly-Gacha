package com.weeklygacha.hook;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Gọi PlayerPoints bằng reflection, nhưng chỉ tra API / phương thức MỘT lần
 * (tra lại khi PlayerPoints được nạp lại). Trước đây mỗi lần mở GUI / quay đều getMethod lại.
 */
public final class PointsHook {

    private static Plugin owner;
    private static Object api;
    private static Method look;
    private static Method take;

    private PointsHook() {
    }

    /** API đã sẵn sàng (PlayerPoints đang bật). */
    public static synchronized boolean ready() {
        Plugin pp = Bukkit.getPluginManager().getPlugin("PlayerPoints");
        if (pp == null || !pp.isEnabled()) {
            owner = null;
            api = null;
            return false;
        }
        if (pp == owner && api != null) return true;
        try {
            Object a = pp.getClass().getMethod("getAPI").invoke(pp);
            look = a.getClass().getMethod("look", UUID.class);
            take = a.getClass().getMethod("take", UUID.class, int.class);
            api = a;
            owner = pp;
            return true;
        } catch (Throwable t) {
            owner = null;
            api = null;
            return false;
        }
    }

    /** Điểm hiện có; -1 nếu PlayerPoints chưa bật. Ném lỗi nếu PlayerPoints báo lỗi. */
    public static int look(UUID uuid) throws Exception {
        if (!ready()) return -1;
        Object r = look.invoke(api, uuid);
        return r instanceof Number n ? n.intValue() : 0;
    }

    /** Trừ điểm; false nếu PlayerPoints chưa bật hoặc không trừ được. */
    public static boolean take(UUID uuid, int amount) throws Exception {
        if (!ready()) return false;
        Object r = take.invoke(api, uuid, amount);
        return !(r instanceof Boolean b) || b;
    }
}
