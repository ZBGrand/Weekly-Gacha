package com.weeklygacha.model;

import org.bukkit.Material;

import java.util.List;

/**
 * Một vật phẩm trong banner.
 *
 * @param id          khoá trong config (duy nhất trong banner)
 * @param provider    nguồn phần thưởng
 * @param itemType    loại MMOItems (chỉ dùng cho MMOITEMS)
 * @param itemId      ID vật phẩm (MMOItems ID / namespace:id của ItemsAdder / tên Material)
 * @param amount      số lượng trao
 * @param rarity      độ hiếm
 * @param chance      tỷ lệ riêng (%), sẽ được chuẩn hoá theo tổng của banner
 * @param featured    vật phẩm nổi bật của banner
 * @param displayName tên hiển thị MiniMessage (có thể null)
 * @param lore        lore MiniMessage (chỉ áp dụng cho VANILLA)
 * @param icon        icon dự phòng cho GUI
 * @param commands    lệnh console chạy khi trúng ({player} = tên người chơi)
 * @param broadcast   ghi đè thông báo toàn server (null = theo độ hiếm)
 */
public record Reward(
        String id,
        RewardProvider provider,
        String itemType,
        String itemId,
        int amount,
        Rarity rarity,
        double chance,
        boolean featured,
        String displayName,
        List<String> lore,
        Material icon,
        List<String> commands,
        Boolean broadcast) {
}
