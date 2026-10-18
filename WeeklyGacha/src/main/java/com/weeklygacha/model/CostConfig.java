package com.weeklygacha.model;

/**
 * Chi phí quay của một banner.
 *
 * @param type   PLAYERPOINTS hoặc ITEM
 * @param single số điểm / số key cho 1 lượt
 * @param ten    số điểm / số key cho 10 lượt
 * @param key    vật phẩm key (chỉ khi type = ITEM)
 */
public record CostConfig(CostType type, int single, int ten, KeyItem key) {
}
