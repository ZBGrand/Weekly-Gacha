package com.weeklygacha.util;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public final class TimeUtil {

    private TimeUtil() {
    }

    /** Ví dụ: "13 ngày 4 giờ 20 phút". */
    public static String duration(long millis) {
        if (millis <= 0) return "0 phút";
        long totalMinutes = millis / 60_000L;
        long days = totalMinutes / 1440;
        long hours = (totalMinutes % 1440) / 60;
        long minutes = totalMinutes % 60;
        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append(" ngày ");
        if (days > 0 || hours > 0) sb.append(hours).append(" giờ ");
        sb.append(minutes).append(" phút");
        return sb.toString();
    }

    public static String date(long epochMillis, ZoneId zone) {
        return DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(zone).format(Instant.ofEpochMilli(epochMillis));
    }
}
