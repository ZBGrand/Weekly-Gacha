package com.weeklygacha.model;

import java.util.Locale;

/** Nguồn tạo ra phần thưởng. */
public enum RewardProvider {
    MMOITEMS(true),
    ITEMSADDER(true),
    VANILLA(true),
    COMMAND(false);

    private final boolean givesItem;

    RewardProvider(boolean givesItem) {
        this.givesItem = givesItem;
    }

    /** true nếu provider tạo ra một ItemStack để đưa vào túi đồ. */
    public boolean givesItem() {
        return givesItem;
    }

    public static RewardProvider parse(String s) {
        if (s == null) return null;
        try {
            return valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
