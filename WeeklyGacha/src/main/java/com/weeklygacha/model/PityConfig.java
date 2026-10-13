package com.weeklygacha.model;

/**
 * Cấu hình bảo hiểm của một banner.
 *
 * @param enabled            có bật pity không
 * @param hard               số lượt tối đa để chắc chắn ra {@code rarity}
 * @param softStart          từ lượt này tỷ lệ của {@code rarity} bắt đầu tăng
 * @param softBoost          mức tăng mỗi lượt: hệ số = 1 + softBoost * (lượt - softStart + 1)
 * @param rarity             độ hiếm được bảo đảm
 * @param guaranteeFeatured  lần trước ra đồ không nổi bật thì lần sau chắc chắn nổi bật
 */
public record PityConfig(boolean enabled, int hard, int softStart, double softBoost,
                         Rarity rarity, boolean guaranteeFeatured) {
}
