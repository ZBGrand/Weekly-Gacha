package com.weeklygacha.model;

/** Một dòng lịch sử quay. */
public record HistoryEntry(long id, String bannerId, int cycle, String rewardId, String rewardName,
                           Rarity rarity, int pityAtPull, long timestamp) {
}
