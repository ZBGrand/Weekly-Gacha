package com.weeklygacha.model;

import java.util.Locale;

/** Độ hiếm của vật phẩm. Thứ tự khai báo = thứ tự tăng dần. */
public enum Rarity {
    COMMON("Thường", "<gray>"),
    UNCOMMON("Khá", "<green>"),
    RARE("Hiếm", "<aqua>"),
    EPIC("Sử thi", "<light_purple>"),
    LEGENDARY("Huyền thoại", "<gold>");

    private final String label;
    private final String color;

    Rarity(String label, String color) {
        this.label = label;
        this.color = color;
    }

    public String label() {
        return label;
    }

    /** Thẻ màu MiniMessage, ví dụ {@code <gold>}. */
    public String color() {
        return color;
    }

    /** Nhãn có màu, ví dụ {@code <gold>Huyền thoại}. */
    public String tagged() {
        return color + label;
    }

    public static Rarity parse(String s, Rarity def) {
        if (s == null) return def;
        try {
            return valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return def;
        }
    }
}
