package com.weeklygacha.model;

import java.util.Locale;

/** Loại chi phí cho một lượt quay. */
public enum CostType {
    /** Trừ điểm PlayerPoints. */
    PLAYERPOINTS,
    /** Trừ vật phẩm (key) trong túi đồ của người chơi. */
    ITEM;

    public static CostType parse(String s) {
        if (s == null) return null;
        try {
            return valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
