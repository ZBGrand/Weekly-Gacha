package com.weeklygacha.model;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Dữ liệu gacha của một người chơi (bộ nhớ đệm). */
public final class PlayerData {

    private final UUID uuid;
    private final Map<String, PlayerBannerData> banners = new ConcurrentHashMap<>();

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

    public Map<String, PlayerBannerData> all() {
        return banners;
    }
}
