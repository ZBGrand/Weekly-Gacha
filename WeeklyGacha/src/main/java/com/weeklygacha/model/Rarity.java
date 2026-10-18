package com.weeklygacha.model;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.Locale;

/**
 * Độ hiếm của vật phẩm. Thứ tự khai báo = thứ tự tăng dần.
 * Tên hiển thị, màu và tấm kính của hoạt ảnh có thể đổi trong gui.yml (mục {@code rarity}).
 */
public enum Rarity {
    COMMON("Thường", "<gray>", Material.LIGHT_GRAY_STAINED_GLASS_PANE),
    UNCOMMON("Khá", "<green>", Material.LIME_STAINED_GLASS_PANE),
    RARE("Hiếm", "<aqua>", Material.LIGHT_BLUE_STAINED_GLASS_PANE),
    EPIC("Sử thi", "<light_purple>", Material.PURPLE_STAINED_GLASS_PANE),
    LEGENDARY("Huyền thoại", "<gold>", Material.ORANGE_STAINED_GLASS_PANE);

    private final String defaultLabel;
    private final String defaultColor;
    private final Material defaultPane;

    private String label;
    private String color;
    private Material pane;

    Rarity(String label, String color, Material pane) {
        this.defaultLabel = label;
        this.defaultColor = color;
        this.defaultPane = pane;
        this.label = label;
        this.color = color;
        this.pane = pane;
    }

    public String label() {
        return label;
    }

    /** Thẻ màu MiniMessage, ví dụ {@code <gold>}. */
    public String color() {
        return color;
    }

    /** Tấm kính màu dùng trong hoạt ảnh quay. */
    public Material pane() {
        return pane;
    }

    /** Nhãn có màu, ví dụ {@code <gold>Huyền thoại}. */
    public String tagged() {
        return color + label;
    }

    /** Áp dụng mục {@code rarity} của gui.yml (null = dùng giá trị mặc định). */
    public static void configure(ConfigurationSection section) {
        for (Rarity r : values()) {
            ConfigurationSection s = section == null ? null : section.getConfigurationSection(r.name());
            r.label = s != null ? s.getString("label", r.defaultLabel) : r.defaultLabel;
            r.color = s != null ? s.getString("color", r.defaultColor) : r.defaultColor;
            Material m = s == null ? null : Material.matchMaterial(s.getString("pane", ""));
            r.pane = m != null && m.isItem() ? m : r.defaultPane;
        }
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
