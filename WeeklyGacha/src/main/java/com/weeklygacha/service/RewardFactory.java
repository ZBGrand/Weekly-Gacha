package com.weeklygacha.service;

import com.weeklygacha.WeeklyGachaPlugin;
import com.weeklygacha.model.Reward;
import com.weeklygacha.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tạo ItemStack / trao thưởng cho từng provider.
 * MMOItems và ItemsAdder được gọi bằng reflection nên plugin build được mà không cần 2 jar đó.
 */
public final class RewardFactory {

    private final WeeklyGachaPlugin plugin;
    private final Map<Reward, ItemStack> iconCache = new ConcurrentHashMap<>();
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    public RewardFactory(WeeklyGachaPlugin plugin) {
        this.plugin = plugin;
    }

    public void clearCache() {
        iconCache.clear();
        warned.clear();
    }

    /** ItemStack thật để trao cho người chơi. null nếu không tạo được hoặc phần thưởng là COMMAND. */
    public ItemStack createItem(Reward r) {
        ItemStack item = switch (r.provider()) {
            case VANILLA -> vanilla(r);
            case MMOITEMS -> mmoItem(r);
            case ITEMSADDER -> itemsAdder(r);
            case COMMAND -> null;
        };
        if (item == null) return null;
        item = item.clone();
        item.setAmount(r.amount());
        return item;
    }

    /** Hình hiển thị trong GUI; luôn có giá trị (rơi về icon cấu hình nếu item chưa sẵn sàng). */
    public ItemStack icon(Reward r) {
        ItemStack cached = iconCache.get(r);
        if (cached != null) return cached.clone();

        ItemStack item = r.provider().givesItem() ? createItem(r) : null;
        boolean resolved = item != null;
        if (item == null) {
            item = new ItemStack(r.icon(), Math.max(1, Math.min(r.amount(), r.icon().getMaxStackSize())));
            if (r.displayName() != null) {
                ItemMeta meta = item.getItemMeta();
                if (meta != null) {
                    meta.displayName(Text.mm(r.displayName()));
                    item.setItemMeta(meta);
                }
            }
        }
        if (resolved || !r.provider().givesItem()) {
            iconCache.put(r, item.clone());
        }
        return item;
    }

    /** Tên thuần văn bản (không thẻ màu) dùng cho lịch sử và thông báo. */
    public String nameOf(Reward r) {
        if (r.displayName() != null) return Text.plain(Text.mm(r.displayName()));
        ItemMeta meta = icon(r).getItemMeta();
        if (meta != null) {
            if (meta.hasDisplayName() && meta.displayName() != null) return Text.plain(meta.displayName());
            if (meta.hasItemName()) return Text.plain(meta.itemName());
        }
        return prettify(r.provider() == com.weeklygacha.model.RewardProvider.VANILLA ? r.itemId() : r.id());
    }

    /** Trả về null nếu vật phẩm dùng được, ngược lại là lý do lỗi. */
    public String check(Reward r) {
        if (!r.provider().givesItem()) return null;
        return createItem(r) == null ? "không tạo được vật phẩm (kiểm tra ID / plugin " + r.provider() + ")" : null;
    }

    /** Trao phần thưởng (gọi trên luồng chính). */
    public void give(Player player, Reward r, ItemStack item) {
        if (item != null) {
            Map<Integer, ItemStack> left = player.getInventory().addItem(item);
            for (ItemStack extra : left.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), extra);
            }
        }
        for (String cmd : r.commands()) {
            String line = cmd.replace("{player}", player.getName()).replace("%player%", player.getName());
            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), line);
            } catch (Exception e) {
                plugin.getLogger().warning("Lệnh thưởng lỗi '" + line + "': " + e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------ provider

    private ItemStack vanilla(Reward r) {
        Material m = r.itemId() == null ? null : Material.matchMaterial(r.itemId());
        if (m == null || !m.isItem()) return null;
        ItemStack item = new ItemStack(m);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (r.displayName() != null) meta.displayName(Text.mm(r.displayName()));
            if (!r.lore().isEmpty()) meta.lore(Text.mmList(r.lore()));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack mmoItem(Reward r) {
        if (!Bukkit.getPluginManager().isPluginEnabled("MMOItems")) {
            warnOnce("mmo-missing", "MMOItems chưa được cài/bật nhưng có vật phẩm MMOITEMS trong config.");
            return null;
        }
        try {
            Class<?> mmoClass = Class.forName("net.Indyuce.mmoitems.MMOItems");
            Object mmo = mmoClass.getField("plugin").get(null);
            Class<?> typeClass = Class.forName("net.Indyuce.mmoitems.api.Type");
            Object type = typeClass.getMethod("get", String.class)
                    .invoke(null, r.itemType().toUpperCase(Locale.ROOT));
            if (type == null) {
                warnOnce("mmo-type-" + r.itemType(), "MMOItems không có type '" + r.itemType() + "'.");
                return null;
            }
            String id = r.itemId().toUpperCase(Locale.ROOT);
            try {
                Method getItem = mmo.getClass().getMethod("getItem", typeClass, String.class);
                Object res = getItem.invoke(mmo, type, id);
                if (res instanceof ItemStack stack) return stack;
            } catch (NoSuchMethodException ignored) {
                // thử API cũ hơn bên dưới
            }
            Method getMmo = mmo.getClass().getMethod("getMMOItem", typeClass, String.class);
            Object mmoItem = getMmo.invoke(mmo, type, id);
            if (mmoItem == null) {
                warnOnce("mmo-" + r.itemType() + id, "Không tìm thấy MMOItem " + r.itemType() + ":" + id);
                return null;
            }
            Object builder = mmoItem.getClass().getMethod("newBuilder").invoke(mmoItem);
            Object built = builder.getClass().getMethod("build").invoke(builder);
            return built instanceof ItemStack stack ? stack : null;
        } catch (Throwable t) {
            warnOnce("mmo-err-" + r.itemType() + r.itemId(),
                    "Lỗi khi lấy MMOItem " + r.itemType() + ":" + r.itemId() + " -> " + t);
            return null;
        }
    }

    private ItemStack itemsAdder(Reward r) {
        if (!Bukkit.getPluginManager().isPluginEnabled("ItemsAdder")) {
            warnOnce("ia-missing", "ItemsAdder chưa được cài/bật nhưng có vật phẩm ITEMSADDER trong config.");
            return null;
        }
        try {
            Class<?> customStack = Class.forName("dev.lone.itemsadder.api.CustomStack");
            Object stack = customStack.getMethod("getInstance", String.class).invoke(null, r.itemId());
            if (stack == null) {
                warnOnce("ia-" + r.itemId(), "ItemsAdder không có item '" + r.itemId()
                        + "' (hoặc dữ liệu chưa tải xong).");
                return null;
            }
            Object item = customStack.getMethod("getItemStack").invoke(stack);
            return item instanceof ItemStack is ? is : null;
        } catch (Throwable t) {
            warnOnce("ia-err-" + r.itemId(), "Lỗi khi lấy item ItemsAdder " + r.itemId() + " -> " + t);
            return null;
        }
    }

    // ------------------------------------------------------------------ tiện ích

    private void warnOnce(String key, String message) {
        if (warned.add(key)) plugin.getLogger().warning(message);
    }

    private static String prettify(String raw) {
        if (raw == null || raw.isEmpty()) return "?";
        String[] parts = raw.toLowerCase(Locale.ROOT).replace(':', '_').split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.toString();
    }
}
