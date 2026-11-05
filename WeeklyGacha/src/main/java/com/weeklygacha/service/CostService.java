package com.weeklygacha.service;

import com.weeklygacha.WeeklyGachaPlugin;
import com.weeklygacha.model.Banner;
import com.weeklygacha.model.CostConfig;
import com.weeklygacha.model.CostType;
import com.weeklygacha.model.KeyItem;
import com.weeklygacha.model.Rarity;
import com.weeklygacha.model.Reward;
import com.weeklygacha.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chi phí quay: điểm PlayerPoints hoặc vật phẩm (key) từ MMOItems / ItemsAdder / vanilla.
 * PlayerPoints, MMOItems và ItemsAdder đều được gọi bằng reflection nên không cần jar lúc build.
 */
public final class CostService {

    private final WeeklyGachaPlugin plugin;
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    public CostService(WeeklyGachaPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ kiểm tra / trừ

    /** Tên plugin còn thiếu/chưa bật để banner này hoạt động; null nếu mọi thứ sẵn sàng. */
    public String problem(Banner banner) {
        CostConfig c = banner.cost();
        if (c.type() == CostType.PLAYERPOINTS) {
            return enabled("PlayerPoints") ? null : "PlayerPoints";
        }
        return switch (c.key().provider()) {
            case MMOITEMS -> enabled("MMOItems") ? null : "MMOItems";
            case ITEMSADDER -> enabled("ItemsAdder") ? null : "ItemsAdder";
            default -> null;
        };
    }

    /** Số điểm hoặc số key người chơi đang có. */
    public long balance(Player player, Banner banner) {
        CostConfig c = banner.cost();
        if (c.type() == CostType.PLAYERPOINTS) {
            return Math.max(0, points(player.getUniqueId()));
        }
        return countKeys(player, c.key());
    }

    public boolean has(Player player, Banner banner, int amount) {
        return amount <= 0 || balance(player, banner) >= amount;
    }

    /** Trừ chi phí. Phải gọi sau has(); trả về false nếu không trừ được (không mất gì). */
    public boolean take(Player player, Banner banner, int amount) {
        if (amount <= 0) return true;
        CostConfig c = banner.cost();
        if (c.type() == CostType.PLAYERPOINTS) {
            return takePoints(player.getUniqueId(), amount);
        }
        return removeKeys(player, c.key(), amount);
    }

    // ------------------------------------------------------------------ hiển thị

    /** Ví dụ "100 điểm" hoặc "3x Chìa khoá Rồng Lửa" (văn bản thuần, không thẻ màu; mẫu chữ trong gui.yml, mục format). */
    public String plain(Banner banner, long amount) {
        CostConfig c = banner.cost();
        if (c.type() == CostType.PLAYERPOINTS) return plugin.gui().points(amount);
        return plugin.gui().keys(amount, keyName(c.key()));
    }

    public String plainBalance(Player player, Banner banner) {
        return plain(banner, balance(player, banner));
    }

    public String keyName(KeyItem key) {
        return plugin.rewards().nameOf(asReward(key));
    }

    /** Icon thể hiện loại chi phí trong GUI. */
    public ItemStack icon(Banner banner) {
        CostConfig c = banner.cost();
        if (c.type() == CostType.PLAYERPOINTS) return new ItemStack(Material.GOLD_NUGGET);
        return plugin.rewards().icon(asReward(c.key()));
    }

    /** Tạo item key thật (dùng cho lệnh givekey và kiểm tra cấu hình); null nếu không tạo được. */
    public ItemStack createKey(Banner banner, int amount) {
        CostConfig c = banner.cost();
        if (c.type() != CostType.ITEM) return null;
        ItemStack item = plugin.rewards().createItem(asReward(c.key()));
        if (item == null) return null;
        item.setAmount(Math.max(1, amount));
        return item;
    }

    /** Lý do cấu hình chi phí lỗi (dùng cho /gachaadmin check); null nếu ổn. */
    public String check(Banner banner) {
        String missing = problem(banner);
        if (missing != null) return "thiếu plugin " + missing;
        if (banner.cost().type() == CostType.ITEM && createKey(banner, 1) == null) {
            return "không tạo được vật phẩm key (kiểm tra ID)";
        }
        return null;
    }

    private Reward asReward(KeyItem key) {
        return new Reward("key", key.provider(), key.itemType(), key.itemId(), 1, Rarity.COMMON, 1, false,
                key.name(), List.of(), key.icon(), List.of(), null);
    }

    // ------------------------------------------------------------------ PlayerPoints (reflection)

    private boolean enabled(String pluginName) {
        Plugin p = Bukkit.getPluginManager().getPlugin(pluginName);
        return p != null && p.isEnabled();
    }

    private Object pointsApi() throws Exception {
        Plugin pp = Bukkit.getPluginManager().getPlugin("PlayerPoints");
        if (pp == null || !pp.isEnabled()) return null;
        return pp.getClass().getMethod("getAPI").invoke(pp);
    }

    private int points(UUID uuid) {
        try {
            Object api = pointsApi();
            if (api == null) return 0;
            Object r = api.getClass().getMethod("look", UUID.class).invoke(api, uuid);
            return r instanceof Number n ? n.intValue() : 0;
        } catch (Throwable t) {
            warnOnce("pp-look", "Không đọc được điểm PlayerPoints: " + t);
            return 0;
        }
    }

    private boolean takePoints(UUID uuid, int amount) {
        try {
            Object api = pointsApi();
            if (api == null) return false;
            Method take = api.getClass().getMethod("take", UUID.class, int.class);
            Object r = take.invoke(api, uuid, amount);
            return !(r instanceof Boolean b) || b;
        } catch (Throwable t) {
            warnOnce("pp-take", "Không trừ được điểm PlayerPoints: " + t);
            return false;
        }
    }

    // ------------------------------------------------------------------ key trong túi đồ

    private long countKeys(Player player, KeyItem key) {
        long total = 0;
        PlayerInventory inv = player.getInventory();
        for (ItemStack item : inv.getStorageContents()) {
            if (matches(key, item)) total += item.getAmount();
        }
        if (matches(key, inv.getItemInOffHand())) total += inv.getItemInOffHand().getAmount();
        return total;
    }

    private boolean removeKeys(Player player, KeyItem key, int amount) {
        if (countKeys(player, key) < amount) return false;
        PlayerInventory inv = player.getInventory();
        int left = amount;
        ItemStack[] contents = inv.getStorageContents();
        for (int i = 0; i < contents.length && left > 0; i++) {
            ItemStack item = contents[i];
            if (!matches(key, item)) continue;
            int take = Math.min(left, item.getAmount());
            left -= take;
            if (take >= item.getAmount()) contents[i] = null;
            else item.setAmount(item.getAmount() - take);
        }
        inv.setStorageContents(contents);
        if (left > 0) {
            ItemStack off = inv.getItemInOffHand();
            if (matches(key, off)) {
                int take = Math.min(left, off.getAmount());
                left -= take;
                if (take >= off.getAmount()) inv.setItemInOffHand(null);
                else off.setAmount(off.getAmount() - take);
            }
        }
        return left == 0;
    }

    /** Item này có phải là key không. */
    boolean matches(KeyItem key, ItemStack item) {
        if (item == null || item.getType().isAir()) return false;
        return switch (key.provider()) {
            case VANILLA -> vanillaMatches(key, item);
            case MMOITEMS -> mmoMatches(key, item);
            case ITEMSADDER -> itemsAdderMatches(key, item);
            default -> false;
        };
    }

    private boolean vanillaMatches(KeyItem key, ItemStack item) {
        Material m = Material.matchMaterial(key.itemId());
        if (m == null || item.getType() != m) return false;
        ItemMeta meta = item.getItemMeta();
        if (key.customModelData() != null) {
            if (meta == null || !meta.hasCustomModelData() || meta.getCustomModelData() != key.customModelData()) {
                return false;
            }
        }
        if (key.name() != null) {
            String want = Text.plain(Text.mm(key.name()));
            String have = meta != null && meta.hasDisplayName() && meta.displayName() != null
                    ? Text.plain(meta.displayName()) : "";
            return want.equalsIgnoreCase(have);
        }
        return true;
    }

    private boolean mmoMatches(KeyItem key, ItemStack item) {
        if (!item.hasItemMeta()) return false;
        try {
            Class<?> mmo = Class.forName("net.Indyuce.mmoitems.MMOItems");
            String id = null;
            try {
                Object r = mmo.getMethod("getID", ItemStack.class).invoke(null, item);
                id = r == null ? null : r.toString();
            } catch (NoSuchMethodException e) {
                // bản cũ/mới hơn: đọc NBT qua MythicLib
                Class<?> nbt = Class.forName("io.lumine.mythic.lib.api.item.NBTItem");
                Object tag = nbt.getMethod("get", ItemStack.class).invoke(null, item);
                Object r = nbt.getMethod("getString", String.class).invoke(tag, "MMOITEMS_ITEM_ID");
                id = r == null ? null : r.toString();
            }
            if (id == null || id.isEmpty() || !id.equalsIgnoreCase(key.itemId())) return false;

            // kiểm tra thêm type nếu API cho phép
            try {
                Object t = mmo.getMethod("getTypeName", ItemStack.class).invoke(null, item);
                String type = t == null ? "" : t.toString();
                return type.isEmpty() || key.itemType() == null || type.equalsIgnoreCase(key.itemType());
            } catch (NoSuchMethodException ignored) {
                return true;
            }
        } catch (Throwable t) {
            warnOnce("mmo-key", "Không kiểm tra được key MMOItems: " + t);
            return false;
        }
    }

    private boolean itemsAdderMatches(KeyItem key, ItemStack item) {
        try {
            Class<?> cs = Class.forName("dev.lone.itemsadder.api.CustomStack");
            Object stack = cs.getMethod("byItemStack", ItemStack.class).invoke(null, item);
            if (stack == null) return false;
            Object nid = cs.getMethod("getNamespacedID").invoke(stack);
            return nid != null && nid.toString().equalsIgnoreCase(key.itemId());
        } catch (Throwable t) {
            warnOnce("ia-key", "Không kiểm tra được key ItemsAdder: " + t);
            return false;
        }
    }

    private void warnOnce(String key, String message) {
        if (warned.add(key.toLowerCase(Locale.ROOT))) plugin.getLogger().warning(message);
    }
}
