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
    /** Icon tạm của vật phẩm chưa lấy được + thời điểm thử lại (không thử lại ở mỗi lần mở GUI). */
    private final Map<Reward, Long> retryAt = new ConcurrentHashMap<>();
    private final Set<String> warned = ConcurrentHashMap.newKeySet();
    private static final long RETRY_MS = 30_000;

    // reflection: tra một lần rồi dùng lại (Class.forName / getMethod mỗi lần quay khá tốn)
    private Object mmoPlugin;
    private Class<?> mmoTypeClass;
    private Method mmoTypeGet;
    private Method mmoGetItem;
    private Method mmoGetMmoItem;
    private Method iaGetInstance;
    private Method iaGetItemStack;
    private boolean mmoResolved;
    private boolean iaResolved;

    /** Tên / lore gốc của phần thưởng đã đọc (đọc lore MMOItems khá tốn: ~10 ms mỗi món). */
    private final Map<Reward, String> nameCache = new ConcurrentHashMap<>();
    private final Map<Reward, java.util.List<String>> loreCache = new ConcurrentHashMap<>();

    /**
     * Kho vật phẩm MMOItems dựng sẵn: mỗi tick dựng trước vài món để lúc quay chỉ việc lấy ra,
     * không phải dựng 10 vũ khí trong cùng một tick. Cấu hình: config.yml, mục performance.
     */
    private record Pooled(ItemStack item, long at) {
    }
    private final Map<Reward, java.util.ArrayDeque<Pooled>> pool = new ConcurrentHashMap<>();
    private int poolCursor;

    public RewardFactory(WeeklyGachaPlugin plugin) {
        this.plugin = plugin;
    }

    public void clearCache() {
        iconCache.clear();
        retryAt.clear();
        nameCache.clear();
        loreCache.clear();
        pool.clear();
        warned.clear();
        mmoResolved = false;
        iaResolved = false;
    }

    /** true nếu icon của phần thưởng đã là vật phẩm thật (không phải icon tạm). */
    public boolean isResolved(Reward r) {
        return iconCache.containsKey(r) && !retryAt.containsKey(r);
    }

    /**
     * Dựng sẵn icon của mọi phần thưởng, rải ra nhiều tick ({@code perTick} icon mỗi tick)
     * để lần mở GUI / xem tỷ lệ đầu tiên không bị khựng.
     */
    public void prewarm(java.util.Collection<com.weeklygacha.model.Banner> banners, int perTick) {
        java.util.ArrayDeque<Reward> queue = new java.util.ArrayDeque<>();
        for (com.weeklygacha.model.Banner b : banners) queue.addAll(b.rewards());
        if (queue.isEmpty()) return;
        new org.bukkit.scheduler.BukkitRunnable() {
            @Override
            public void run() {
                for (int i = 0; i < perTick && !queue.isEmpty(); i++) icon(queue.poll());
                if (queue.isEmpty()) cancel();
            }
        }.runTaskTimer(plugin, 20L, 1L);
    }

    /** ItemStack thật để trao cho người chơi. null nếu không tạo được hoặc phần thưởng là COMMAND. */
    public ItemStack createItem(Reward r) {
        ItemStack item = switch (r.provider()) {
            case VANILLA -> vanilla(r);
            case MMOITEMS -> mmoItem(r);
            case ITEMSADDER -> itemsAdder(r);
            case COMMAND, LUNACY -> null;
        };
        if (item == null) return null;
        item = item.clone();
        item.setAmount(r.amount());
        return item;
    }

    /** Hình hiển thị trong GUI; luôn có giá trị (rơi về icon cấu hình nếu item chưa sẵn sàng). */
    public ItemStack icon(Reward r) {
        ItemStack cached = iconCache.get(r);
        if (cached != null) {
            Long retry = retryAt.get(r);
            if (retry == null || System.currentTimeMillis() < retry) return cached.clone();
        }

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
        iconCache.put(r, item.clone());
        if (resolved || !r.provider().givesItem()) retryAt.remove(r);
        else retryAt.put(r, System.currentTimeMillis() + RETRY_MS);
        return item;
    }

    /** Tên thuần văn bản (không thẻ màu) dùng cho lịch sử và thông báo. Được cache khi icon đã là vật phẩm thật. */
    public String nameOf(Reward r) {
        String hit = nameCache.get(r);
        if (hit != null) return hit;
        String name = readName(r);
        if (r.displayName() != null || isResolved(r)) nameCache.put(r, name);
        return name;
    }

    /**
     * Lore gốc của vật phẩm (vd chỉ số vũ khí MMOItems) dạng MiniMessage, cache theo phần thưởng.
     * Chỉ được gọi khi gui.yml thật sự dùng khối {item-lore}.
     */
    public java.util.List<String> rawLore(Reward r) {
        java.util.List<String> hit = loreCache.get(r);
        if (hit != null) return hit;
        ItemMeta meta = icon(r).getItemMeta();
        java.util.List<net.kyori.adventure.text.Component> lore = meta == null ? null : meta.lore();
        java.util.List<String> out = new java.util.ArrayList<>();
        if (lore != null) {
            net.kyori.adventure.text.minimessage.MiniMessage mm = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage();
            for (net.kyori.adventure.text.Component line : lore) out.add(mm.serialize(line));
        }
        java.util.List<String> result = java.util.List.copyOf(out);
        if (isResolved(r)) loreCache.put(r, result);
        return result;
    }

    // ------------------------------------------------------------------ kho dựng sẵn

    /** Lấy vật phẩm để trao: ưu tiên món đã dựng sẵn trong kho, hết thì dựng ngay. */
    public ItemStack takeItem(Reward r) {
        java.util.ArrayDeque<Pooled> q = pool.get(r);
        if (q != null) {
            long maxAge = plugin.getConfig().getLong("performance.item-pool-max-age-seconds", 300) * 1000L;
            long now = System.currentTimeMillis();
            Pooled p;
            while ((p = q.pollFirst()) != null) {
                if (now - p.at() <= maxAge) return p.item();
            }
        }
        return createItem(r);
    }

    /** Chạy mỗi tick: bổ sung kho cho các phần thưởng MMOItems còn thiếu (tối đa N món mỗi tick). */
    public void startPool() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::fillPool, 40L, 1L);
    }

    private void fillPool() {
        int size = plugin.getConfig().getInt("performance.item-pool-size", 3);
        int perTick = plugin.getConfig().getInt("performance.item-pool-builds-per-tick", 1);
        if (size <= 0 || perTick <= 0) return;
        java.util.List<Reward> all = new java.util.ArrayList<>();
        for (com.weeklygacha.model.Banner b : plugin.banners().all()) {
            for (Reward r : b.rewards()) {
                if (r.provider() == com.weeklygacha.model.RewardProvider.MMOITEMS && isResolved(r)) all.add(r);
            }
        }
        if (all.isEmpty()) return;
        long maxAge = plugin.getConfig().getLong("performance.item-pool-max-age-seconds", 300) * 1000L;
        long now = System.currentTimeMillis();
        int built = 0;
        for (int k = 0; k < all.size() && built < perTick; k++) {
            Reward r = all.get(Math.floorMod(poolCursor++, all.size()));
            java.util.ArrayDeque<Pooled> q = pool.computeIfAbsent(r, x -> new java.util.ArrayDeque<>());
            while (!q.isEmpty() && now - q.peekFirst().at() > maxAge) q.pollFirst();   // bỏ món quá cũ
            if (q.size() >= size) continue;
            ItemStack item = createItem(r);
            if (item != null) q.addLast(new Pooled(item, now));
            built++;
        }
    }

    private String readName(Reward r) {
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
        if (r.provider() == com.weeklygacha.model.RewardProvider.LUNACY) {
            plugin.lunacy().give(player.getUniqueId(), player.getName(), r.amount(), "REWARD:" + r.id());
        }
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

    private boolean resolveMmo() {
        if (mmoResolved) return mmoPlugin != null;
        mmoResolved = true;
        try {
            Class<?> mmoClass = Class.forName("net.Indyuce.mmoitems.MMOItems");
            mmoPlugin = mmoClass.getField("plugin").get(null);
            mmoTypeClass = Class.forName("net.Indyuce.mmoitems.api.Type");
            mmoTypeGet = mmoTypeClass.getMethod("get", String.class);
            try {
                mmoGetItem = mmoPlugin.getClass().getMethod("getItem", mmoTypeClass, String.class);
            } catch (NoSuchMethodException ignored) {
                mmoGetItem = null; // API cũ hơn: dùng getMMOItem + builder
            }
            try {
                mmoGetMmoItem = mmoPlugin.getClass().getMethod("getMMOItem", mmoTypeClass, String.class);
            } catch (NoSuchMethodException ignored) {
                mmoGetMmoItem = null;
            }
            return true;
        } catch (Throwable t) {
            warnOnce("mmo-api", "Không dùng được API MMOItems: " + t);
            mmoPlugin = null;
            return false;
        }
    }

    private ItemStack mmoItem(Reward r) {
        if (!Bukkit.getPluginManager().isPluginEnabled("MMOItems")) {
            warnOnce("mmo-missing", "MMOItems chưa được cài/bật nhưng có vật phẩm MMOITEMS trong config.");
            return null;
        }
        if (!resolveMmo()) return null;
        try {
            Object type = mmoTypeGet.invoke(null, r.itemType().toUpperCase(Locale.ROOT));
            if (type == null) {
                warnOnce("mmo-type-" + r.itemType(), "MMOItems không có type '" + r.itemType() + "'.");
                return null;
            }
            String id = r.itemId().toUpperCase(Locale.ROOT);
            if (mmoGetItem != null) {
                Object res = mmoGetItem.invoke(mmoPlugin, type, id);
                if (res instanceof ItemStack stack) return stack;
            }
            if (mmoGetMmoItem == null) {
                warnOnce("mmo-" + r.itemType() + id, "Không tìm thấy MMOItem " + r.itemType() + ":" + id);
                return null;
            }
            Object mmoItem = mmoGetMmoItem.invoke(mmoPlugin, type, id);
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
            if (!iaResolved) {
                iaResolved = true;
                Class<?> customStack = Class.forName("dev.lone.itemsadder.api.CustomStack");
                iaGetInstance = customStack.getMethod("getInstance", String.class);
                iaGetItemStack = customStack.getMethod("getItemStack");
            }
            if (iaGetInstance == null) return null;
            Object stack = iaGetInstance.invoke(null, r.itemId());
            if (stack == null) {
                warnOnce("ia-" + r.itemId(), "ItemsAdder không có item '" + r.itemId()
                        + "' (hoặc dữ liệu chưa tải xong).");
                return null;
            }
            Object item = iaGetItemStack.invoke(stack);
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
