package com.weeklygacha.model;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Dữ liệu gacha của một người chơi (bộ nhớ đệm). */
public final class PlayerData {

    private final UUID uuid;
    private final Map<String, PlayerBannerData> banners = new ConcurrentHashMap<>();
    /** Số dư Lunacy (bản sao trong bộ nhớ; data.db là nguồn gốc, cập nhật bằng cộng/trừ chênh lệch). */
    private final java.util.concurrent.atomic.AtomicLong lunacy = new java.util.concurrent.atomic.AtomicLong();

    public PlayerData(UUID uuid) {
        this.uuid = uuid;
    }

    public UUID uuid() {
        return uuid;
    }

    public PlayerBannerData get(String bannerId) {
        return banners.computeIfAbsent(bannerId, k -> new PlayerBannerData());
    }

    public void put(String bannerId, PlayerBannerData data) {
        banners.put(bannerId, data);
    }

    public java.util.concurrent.atomic.AtomicLong lunacy() {
        return lunacy;
    }

    public Map<String, PlayerBannerData> all() {
        return banners;
    }
}
