package com.weeklygacha.util;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Định dạng thời gian. Chữ "ngày/giờ/phút" và mẫu ngày chỉnh trong gui.yml (mục {@code format}). */
public final class TimeUtil {

    private static volatile String dayWord = "ngày";
    private static volatile String hourWord = "giờ";
    private static volatile String minuteWord = "phút";
    private static volatile DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private TimeUtil() {
    }

    public static void configure(String day, String hour, String minute, String datePattern) {
        dayWord = day;
        hourWord = hour;
        minuteWord = minute;
        try {
            dateFormat = DateTimeFormatter.ofPattern(datePattern);
        } catch (IllegalArgumentException e) {
            dateFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
        }
    }

    /** Ví dụ: "13 ngày 4 giờ 20 phút". */
    public static String duration(long millis) {
        if (millis <= 0) return "0 " + minuteWord;
        long totalMinutes = millis / 60_000L;
        long days = totalMinutes / 1440;
        long hours = (totalMinutes % 1440) / 60;
        long minutes = totalMinutes % 60;
        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append(' ').append(dayWord).append(' ');
        if (days > 0 || hours > 0) sb.append(hours).append(' ').append(hourWord).append(' ');
        sb.append(minutes).append(' ').append(minuteWord);
        return sb.toString();
    }

    public static String date(long epochMillis, ZoneId zone) {
        return dateFormat.withZone(zone).format(Instant.ofEpochMilli(epochMillis));
    }
}
