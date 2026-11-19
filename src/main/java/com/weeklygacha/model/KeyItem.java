package com.weeklygacha.model;

import org.bukkit.Material;

/**
 * Vật phẩm dùng làm "key" để quay.
 *
 * @param provider        MMOITEMS, ITEMSADDER hoặc VANILLA (VANILLA có thể khớp thêm theo tên/CustomModelData
 *                        để nhận key của plugin khác)
 * @param itemType        loại MMOItems (chỉ MMOITEMS)
 * @param itemId          ID MMOItems / namespace:id ItemsAdder / tên Material
 * @param name            tên MiniMessage; VANILLA: tên bắt buộc phải khớp (null = không kiểm tra tên)
 * @param customModelData VANILLA: CustomModelData bắt buộc (null = không kiểm tra)
 * @param icon            icon hiển thị khi không tạo được item thật
 */
public record KeyItem(RewardProvider provider, String itemType, String itemId, String name,
                      Integer customModelData, Material icon) {
}
