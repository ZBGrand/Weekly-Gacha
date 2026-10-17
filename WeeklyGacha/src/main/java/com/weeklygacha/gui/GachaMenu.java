package com.weeklygacha.gui;

import com.weeklygacha.WeeklyGachaPlugin;
import com.weeklygacha.model.Banner;
import com.weeklygacha.model.BannerType;
import com.weeklygacha.model.HistoryEntry;
import com.weeklygacha.model.PlayerBannerData;
import com.weeklygacha.model.PlayerData;
import com.weeklygacha.model.PullEntry;
import com.weeklygacha.model.PullResult;
import com.weeklygacha.model.Rarity;
import com.weeklygacha.model.Reward;
import com.weeklygacha.model.SparkConfig;
import com.weeklygacha.service.BannerManager;
import com.weeklygacha.util.Items;
import com.weeklygacha.util.Text;
import com.weeklygacha.util.TimeUtil;
import net.kyori.adventure.text.Component;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Toàn bộ giao diện chest GUI: menu chính, spark, tỷ lệ, lịch sử, kết quả quay (có hoạt ảnh).
 * Mọi chuỗi hiển thị (MiniMessage) của GUI nằm trong file này.
 */
public final class GachaMenu {

    private static final int PAGE_SIZE = 45;
    private static final String TITLE_MAIN = "<dark_gray>✦ <gradient:#ffb347:#ffcc33>Gacha Hàng Tuần</gradient> <dark_gray>✦";

    private final WeeklyGachaPlugin plugin;
    private final Map<UUID, String> selected = new ConcurrentHashMap<>();
    /** Người chơi đã tắt hoạt ảnh (chỉ trong phiên, mất khi thoát game/khởi động lại). */
    private final Set<UUID> animationOff = ConcurrentHashMap.newKeySet();

    public GachaMenu(WeeklyGachaPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ chọn banner / tuỳ chọn

    /** Banner người chơi đang chọn; nếu không hợp lệ thì lấy banner giới hạn hiện tại, rồi banner đầu tiên đang mở. */
    public Banner selectedBanner(Player player) {
        BannerManager bm = plugin.banners();
        Banner b = bm.get(selected.get(player.getUniqueId()));
        if (b != null && bm.isActive(b)) return b;
        Banner limited = bm.currentLimited();
        if (limited != null) return limited;
        List<Banner> active = bm.activeBanners();
        return active.isEmpty() ? null : active.get(0);
    }

    public void forget(UUID uuid) {
        selected.remove(uuid);
        animationOff.remove(uuid);
    }

    public boolean animationEnabled(Player player) {
        return plugin.getConfig().getBoolean("animation.enabled", true) && !animationOff.contains(player.getUniqueId());
    }

    /** Bật/tắt hoạt ảnh cho người chơi; trả về trạng thái mới (true = bật). */
    public boolean toggleAnimation(Player player) {
        UUID id = player.getUniqueId();
        if (animationOff.remove(id)) return true;
        animationOff.add(id);
        return false;
    }

    // ------------------------------------------------------------------ menu chính

    public void openMain(Player player, Banner banner) {
        BannerManager bm = plugin.banners();
        Banner current = banner != null ? banner : selectedBanner(player);
        if (current == null) {
            plugin.messages().send(player, "no-banner");
            return;
        }
        selected.put(player.getUniqueId(), current.id());

        PlayerData data = plugin.players().get(player.getUniqueId());
        PlayerBannerData pd = data != null ? data.get(current.id()) : new PlayerBannerData();

        MenuHolder holder = new MenuHolder();
        Inventory inv = Bukkit.createInventory(holder, 54, Text.mm(TITLE_MAIN));
        holder.bind(inv);
        holder.fill(Items.filler());

        // --- thanh chọn banner (hàng 2)
        List<Banner> active = bm.activeBanners();
        int n = Math.min(active.size(), 7);
        int start = 13 - (n - 1) / 2;
        for (int i = 0; i < n; i++) {
            Banner b = active.get(i);
            boolean sel = b.id().equals(current.id());
            List<String> lore = new ArrayList<>();
            lore.add(b.type() == BannerType.LIMITED ? "<light_purple>✦ Banner giới hạn" : "<green>✦ Banner thường (vĩnh viễn)");
            if (b.type() == BannerType.LIMITED) {
                lore.add("<gray>Còn lại: <white>" + TimeUtil.duration(bm.remainingMillis()));
            }
            lore.add("<gray>Quay x1: <gold>" + money(b.singleCost()));
            lore.add(sel ? "<yellow>▶ Đang chọn" : "<yellow>Nhấn để chọn");
            ItemStack tab = Items.of(b.icon(), 1, b.displayName(), lore);
            if (sel) Items.glow(tab);
            holder.set(start + i, tab, (p, c) -> openMain(p, b));
        }

        // --- thông tin banner + vật phẩm nổi bật (hàng 3)
        List<String> info = new ArrayList<>(current.description());
        info.add("");
        if (current.type() == BannerType.LIMITED) {
            info.add("<gray>Chu kỳ #" + bm.cycleNumber() + " — còn lại <white>" + TimeUtil.duration(bm.remainingMillis()));
            if (bm.hasOverride()) info.add("<gray>(Banner do quản trị viên chọn)");
        } else {
            info.add("<gray>Banner vĩnh viễn.");
        }
        holder.set(22, Items.glow(Items.of(current.icon(), 1, current.displayName(), info)), null);

        List<Reward> showcase = showcase(current);
        if (!showcase.isEmpty()) holder.set(21, rewardDisplay(showcase.get(0), current, true, List.of()), null);
        if (showcase.size() > 1) holder.set(23, rewardDisplay(showcase.get(1), current, true, List.of()), null);

        // --- nút quay (hàng 4)
        holder.set(29, Items.of(Material.EMERALD, 1, "<green><bold>Quay x1", List.of(
                "<gray>Chi phí: <gold>" + money(current.singleCost()),
                "",
                "<yellow>Nhấn để quay")), (p, c) -> doPull(p, current, 1));

        List<String> tenLore = new ArrayList<>();
        tenLore.add("<gray>Chi phí: <gold>" + money(current.tenCost()));
        Rarity tg = current.spark().tenGuarantee();
        if (tg != null) tenLore.add("<gray>Đảm bảo ít nhất 1 vật phẩm " + tg.tagged() + "<gray> trở lên.");
        double saved = current.singleCost() * 10 - current.tenCost();
        if (saved > 0) tenLore.add("<gray>Tiết kiệm: <green>" + money(saved));
        tenLore.add("");
        tenLore.add("<yellow>Nhấn để quay");
        holder.set(33, Items.of(Material.DIAMOND, 10, "<aqua><bold>Quay x10", tenLore), (p, c) -> doPull(p, current, 10));

        // --- spark (giữa hàng 4)
        SparkConfig sp = current.spark();
        boolean ready = sp.enabled() && pd.getSpark() >= sp.threshold();
        if (ready) {
            List<String> lore = new ArrayList<>(sparkLines(current, pd));
            lore.add("");
            lore.add("<yellow>Nhấn để chọn vật phẩm và đổi!");
            holder.set(31, Items.glow(Items.of(Material.NETHER_STAR, 1, "<gold><bold>SPARK — Sẵn sàng đổi!", lore)),
                    (p, c) -> openSparkSelect(p, current));
        } else {
            holder.set(31, Items.of(Material.EXPERIENCE_BOTTLE, 1, "<yellow><bold>Spark", sparkLines(current, pd)), null);
        }

        // --- hàng 5: tiện ích
        holder.set(38, Items.of(Material.BOOK, 1, "<white><bold>Tỷ lệ vật phẩm",
                List.of("<gray>Xem tỷ lệ riêng của từng vật phẩm.")), (p, c) -> openRates(p, current, 0));
        holder.set(40, Items.of(animationEnabled(player) ? Material.LIME_DYE : Material.GRAY_DYE, 1,
                "<white><bold>Hoạt ảnh: " + (animationEnabled(player) ? "<green>Bật" : "<red>Tắt"),
                List.of("<gray>Hoạt ảnh lật thẻ khi quay.", "<yellow>Nhấn để bật/tắt")), (p, c) -> {
            toggleAnimation(p);
            openMain(p, current);
        });
        holder.set(42, Items.of(Material.WRITABLE_BOOK, 1, "<white><bold>Lịch sử quay",
                List.of("<gray>Các lượt quay gần đây của bạn.")), (p, c) -> openHistory(p, 0));
        holder.set(49, Items.of(Material.BARRIER, 1, "<red><bold>Đóng", List.of()), (p, c) -> p.closeInventory());

        player.openInventory(inv);
    }

    private void doPull(Player player, Banner banner, int count) {
        PullResult result = plugin.gacha().pull(player, banner, count);
        if (result.success()) {
            openResult(player, banner, result, count);
        }
    }

    // ------------------------------------------------------------------ spark

    /** Dòng mô tả spark — dùng cho GUI và lệnh /gacha spark. */
    public List<String> sparkLines(Banner b, PlayerBannerData pd) {
        SparkConfig sp = b.spark();
        List<String> l = new ArrayList<>();
        if (!sp.enabled()) {
            l.add("<gray>Banner này không có spark.");
            l.add("<gray>Tổng lượt đã quay: <white>" + pd.getTotalPulls());
            return l;
        }
        int shown = Math.min(pd.getSpark(), sp.threshold());
        l.add("<gray>Tiến độ: " + bar(shown, sp.threshold()) + " <white>" + pd.getSpark() + "<gray>/<white>" + sp.threshold());
        if (pd.getSpark() >= sp.threshold()) {
            l.add("<green>Đã đủ điểm: có thể đổi " + (pd.getSpark() / sp.threshold()) + " lần.");
        } else {
            l.add("<gray>Còn <white>" + (sp.threshold() - pd.getSpark()) + "<gray> lượt nữa để đổi.");
        }
        l.add("<gray>Đổi lấy 1 vật phẩm " + sp.rarity().tagged() + "<gray> do bạn tự chọn.");
        l.add("<gray>Từ lần " + sp.rarity().tagged() + "<gray> gần nhất: <white>" + pd.getPity() + "<gray> lượt");
        l.add("<gray>Tổng lượt đã quay: <white>" + pd.getTotalPulls());
        return l;
    }

    private static String bar(int value, int max) {
        int cells = 20;
        int filled = max <= 0 ? 0 : Math.min(cells, (int) Math.round((double) value / max * cells));
        return "<green>" + "▮".repeat(filled) + "<dark_gray>" + "▮".repeat(cells - filled);
    }

    private List<String> sparkRules(Banner b) {
        SparkConfig sp = b.spark();
        if (!sp.enabled()) return List.of("<gray>Banner này không có spark.");
        List<String> l = new ArrayList<>();
        l.add("<gray>Mỗi lượt quay tích <white>1<gray> điểm spark.");
        l.add("<gray>Đủ <white>" + sp.threshold() + "<gray> điểm: đổi 1 vật phẩm " + sp.rarity().tagged() + "<gray>.");
        l.add(b.featuredPool().isEmpty()
                ? "<gray>Chọn trong mọi vật phẩm cùng độ hiếm."
                : "<gray>Chỉ chọn trong các vật phẩm <yellow>nổi bật<gray>.");
        l.add("<gray>Ra " + sp.rarity().tagged() + "<gray> khi quay <white>không<gray> làm mất điểm spark.");
        if (sp.tenGuarantee() != null) {
            l.add("<gray>Quay x10 đảm bảo ≥ 1 vật phẩm " + sp.tenGuarantee().tagged() + "<gray> trở lên.");
        }
        l.add("<dark_gray>Spark tính riêng cho từng banner.");
        return l;
    }

    public void openSparkSelect(Player player, Banner banner) {
        List<Reward> pool = banner.sparkPool();
        MenuHolder holder = new MenuHolder();
        Inventory inv = Bukkit.createInventory(holder, 36,
                Text.mm("<dark_gray>Spark — chọn vật phẩm"));
        holder.bind(inv);
        holder.fill(Items.filler());

        int shown = Math.min(pool.size(), 7);
        int start = 13 - (shown - 1) / 2;
        for (int i = 0; i < shown; i++) {
            Reward r = pool.get(i);
            holder.set(start + i, rewardDisplay(r, banner, false, List.of("", "<yellow>Nhấn để chọn")),
                    (p, c) -> openSparkConfirm(p, banner, r));
        }
        holder.set(31, Items.of(Material.OAK_DOOR, 1, "<yellow>Quay lại", List.of()), (p, c) -> openMain(p, banner));
        player.openInventory(inv);
    }

    private void openSparkConfirm(Player player, Banner banner, Reward reward) {
        MenuHolder holder = new MenuHolder();
        Inventory inv = Bukkit.createInventory(holder, 27, Text.mm("<dark_gray>Xác nhận đổi spark"));
        holder.bind(inv);
        holder.fill(Items.filler());
        holder.set(13, rewardDisplay(reward, banner, false, List.of()), null);
        holder.set(11, Items.of(Material.LIME_CONCRETE, 1, "<green><bold>Xác nhận",
                List.of("<gray>Trừ <white>" + banner.spark().threshold() + "<gray> điểm spark.")), (p, c) -> {
            if (plugin.gacha().exchange(p, banner, reward)) {
                p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            }
            openMain(p, banner);
        });
        holder.set(15, Items.of(Material.RED_CONCRETE, 1, "<red><bold>Huỷ", List.of()), (p, c) -> openSparkSelect(p, banner));
        player.openInventory(inv);
    }

    private List<Reward> showcase(Banner b) {
        List<Reward> pool = !b.featuredPool().isEmpty() ? b.featuredPool() : b.topPool();
        if (pool.isEmpty()) {
            pool = new ArrayList<>(b.rewards());
            pool.sort(Comparator.comparingInt((Reward r) -> r.rarity().ordinal()).reversed()
                    .thenComparingDouble(Reward::chance));
        }
        return pool.size() > 2 ? pool.subList(0, 2) : pool;
    }

    // ------------------------------------------------------------------ tỷ lệ

    public void openRates(Player player, Banner banner, int page) {
        List<Reward> sorted = new ArrayList<>(banner.rewards());
        sorted.sort(Comparator.comparingInt((Reward r) -> r.rarity().ordinal()).reversed()
                .thenComparingDouble(Reward::chance));
        int pages = Math.max(1, (sorted.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int pg = Math.max(0, Math.min(page, pages - 1));

        MenuHolder holder = new MenuHolder();
        Inventory inv = Bukkit.createInventory(holder, 54,
                Text.mm("<dark_gray>Tỷ lệ — ").append(Text.mm(banner.displayName())));
        holder.bind(inv);
        holder.fill(Items.filler());

        int from = pg * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && from + i < sorted.size(); i++) {
            Reward r = sorted.get(from + i);
            holder.set(i, rewardDisplay(r, banner, true, List.of()), null);
        }

        navigation(holder, pg, pages, p -> openMain(p, banner), p -> openRates(p, banner, pg - 1), p -> openRates(p, banner, pg + 1));
        holder.set(53, Items.of(Material.PAPER, 1, "<yellow><bold>Quy tắc Spark", sparkRules(banner)), null);
        player.openInventory(inv);
    }

    // ------------------------------------------------------------------ lịch sử

    public void openHistory(Player player, int page) {
        int pg = Math.max(0, page);
        UUID uuid = player.getUniqueId();
        plugin.database().history(uuid, PAGE_SIZE + 1, pg * PAGE_SIZE).whenComplete((list, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) return;
                    if (error != null || list == null) {
                        plugin.messages().send(player, "history-error");
                        return;
                    }
                    buildHistory(player, pg, list);
                }));
    }

    private void buildHistory(Player player, int page, List<HistoryEntry> entries) {
        boolean hasNext = entries.size() > PAGE_SIZE;
        BannerManager bm = plugin.banners();

        MenuHolder holder = new MenuHolder();
        Inventory inv = Bukkit.createInventory(holder, 54, Text.mm("<dark_gray>Lịch sử quay — trang " + (page + 1)));
        holder.bind(inv);
        holder.fill(Items.filler());

        for (int i = 0; i < Math.min(PAGE_SIZE, entries.size()); i++) {
            HistoryEntry e = entries.get(i);
            Banner b = bm.get(e.bannerId());
            Reward r = b != null ? b.find(e.rewardId()) : null;
            ItemStack base = r != null ? plugin.rewards().icon(r) : new ItemStack(Material.PAPER);
            Component bannerName = b != null ? Text.mm(b.displayName()) : Text.mm("<gray>" + Text.escape(e.bannerId()));
            List<Component> lore = new ArrayList<>();
            lore.add(Text.mm("<gray>Độ hiếm: " + e.rarity().tagged()));
            lore.add(Text.mm("<gray>Banner: ").append(bannerName));
            lore.add(Text.mm("<gray>Chu kỳ: <white>#" + e.cycle()));
            lore.add(Text.mm(e.pityAtPull() > 0
                    ? "<gray>Ra sau: <white>" + e.pityAtPull() + "<gray> lượt kể từ lần hiếm trước"
                    : "<aqua>Đổi bằng spark"));
            lore.add(Text.mm("<dark_gray>" + TimeUtil.date(e.timestamp(), bm.zone())));
            holder.set(i, Items.decorate(base, Text.mm(e.rarity().color() + Text.escape(e.rewardName())), lore), null);
        }

        Banner back = selectedBanner(player);
        if (page > 0) {
            holder.set(48, Items.of(Material.ARROW, 1, "<yellow>◀ Trang trước", List.of()), (p, c) -> openHistory(p, page - 1));
        }
        if (hasNext) {
            holder.set(50, Items.of(Material.ARROW, 1, "<yellow>Trang sau ▶", List.of()), (p, c) -> openHistory(p, page + 1));
        }
        holder.set(49, Items.of(Material.OAK_DOOR, 1, "<yellow>Quay lại", List.of()), (p, c) -> openMain(p, back));
        if (entries.isEmpty()) {
            holder.set(22, Items.of(Material.BARRIER, 1, "<gray>Chưa có lượt quay nào", List.of()), null);
        }
        player.openInventory(inv);
    }

    // ------------------------------------------------------------------ kết quả quay (+ hoạt ảnh)

    /**
     * Hiển thị kết quả quay. Phần thưởng đã được trao trước khi gọi hàm này; hoạt ảnh chỉ mang tính hiển thị.
     */
    public void openResult(Player player, Banner banner, PullResult result, int count) {
        List<PullEntry> entries = result.entries();
        MenuHolder holder = new MenuHolder();
        Inventory inv = Bukkit.createInventory(holder, 36, Text.mm("<dark_gray>Kết quả quay x" + count));
        holder.bind(inv);
        holder.fill(background());

        int[] slots = entries.size() == 1 ? new int[]{13} : new int[]{11, 12, 13, 14, 15, 20, 21, 22, 23, 24};

        if (!animationEnabled(player)) {
            for (int i = 0; i < entries.size() && i < slots.length; i++) {
                holder.set(slots[i], cardFor(entries.get(i), banner), null);
            }
            showFinal(holder, player, banner, result, count);
            player.openInventory(inv);
            plugin.gacha().announce(player, banner, result);
            return;
        }

        GachaAnimation animation = new GachaAnimation(plugin, this, player, holder, banner, result, count, slots);
        animation.prepare();
        holder.set(GachaAnimation.SKIP_SLOT, Items.of(Material.BARRIER, 1, "<red><bold>Bỏ qua",
                List.of("<gray>Hiện ngay toàn bộ kết quả.")), (p, c) -> animation.skip());
        player.openInventory(inv);
        animation.runTaskTimer(plugin, 10L, GachaAnimation.PERIOD);
    }

    /** Hoàn thiện màn hình kết quả: tóm tắt, nút quay lại / quay tiếp. */
    void showFinal(MenuHolder holder, Player player, Banner banner, PullResult result, int count) {
        Map<Rarity, Integer> tally = new EnumMap<>(Rarity.class);
        Rarity best = Rarity.COMMON;
        for (PullEntry e : result.entries()) {
            tally.merge(e.reward().rarity(), 1, Integer::sum);
            if (e.reward().rarity().ordinal() > best.ordinal()) best = e.reward().rarity();
        }

        List<String> summary = new ArrayList<>();
        summary.add("<gray>Banner: <white>" + Text.plain(Text.mm(banner.displayName())));
        for (Rarity r : Rarity.values()) {
            int c = tally.getOrDefault(r, 0);
            if (c > 0) summary.add(r.tagged() + "<gray>: <white>" + c);
        }
        holder.set(4, Items.of(Material.NETHER_STAR, 1, "<gold><bold>Kết quả quay x" + count, summary), null);
        holder.set(GachaAnimation.SKIP_SLOT, background(), null);

        holder.set(30, Items.of(Material.OAK_DOOR, 1, "<yellow>Quay lại", List.of()), (p, c) -> openMain(p, banner));
        double cost = count == 10 ? banner.tenCost() : banner.singleCost() * count;
        holder.set(32, Items.of(count == 10 ? Material.DIAMOND : Material.EMERALD, 1, "<green><bold>Quay tiếp x" + count,
                List.of("<gray>Chi phí: <gold>" + money(cost))), (p, c) -> doPull(p, banner, count));

        Sound sound = best.ordinal() >= Rarity.EPIC.ordinal()
                ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.ENTITY_PLAYER_LEVELUP;
        player.playSound(player.getLocation(), sound, 1f, 1f);
    }

    /** Thẻ hiển thị một lượt quay (vật phẩm + độ hiếm + số lượt). */
    ItemStack cardFor(PullEntry e, Banner banner) {
        return rewardDisplay(e.reward(), banner, false,
                List.of("<gray>Ra sau: <white>" + e.pityAtPull() + "<gray> lượt kể từ lần hiếm trước"));
    }

    ItemStack background() {
        return Items.of(Material.BLACK_STAINED_GLASS_PANE, 1, " ", List.of());
    }

    // ------------------------------------------------------------------ tiện ích

    /** Đóng mọi menu của plugin (khi reload hoặc đổi banner). */
    public void closeAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof MenuHolder) {
                p.closeInventory();
            }
        }
    }

    private interface PlayerAction {
        void run(Player player);
    }

    private void navigation(MenuHolder holder, int page, int pages, PlayerAction back, PlayerAction prev, PlayerAction next) {
        if (page > 0) holder.set(48, Items.of(Material.ARROW, 1, "<yellow>◀ Trang trước", List.of()), (p, c) -> prev.run(p));
        holder.set(49, Items.of(Material.OAK_DOOR, 1, "<yellow>Quay lại", List.of("<gray>Trang " + (page + 1) + "/" + pages)),
                (p, c) -> back.run(p));
        if (page < pages - 1) holder.set(50, Items.of(Material.ARROW, 1, "<yellow>Trang sau ▶", List.of()), (p, c) -> next.run(p));
    }

    private ItemStack rewardDisplay(Reward r, Banner banner, boolean showRate, List<String> extra) {
        ItemStack base = plugin.rewards().icon(r);
        String name = plugin.rewards().nameOf(r);
        List<Component> lore = new ArrayList<>();
        lore.add(Text.mm("<gray>Độ hiếm: " + r.rarity().tagged()));
        if (r.amount() > 1) lore.add(Text.mm("<gray>Số lượng: <white>x" + r.amount()));
        if (showRate) lore.add(Text.mm("<gray>Tỷ lệ: <white>" + pct(banner.ratePercent(r))));
        if (r.featured()) lore.add(Text.mm("<yellow>★ Vật phẩm nổi bật"));
        for (String line : extra) lore.add(Text.mm(line));
        return Items.decorate(base, Text.mm(r.rarity().color() + Text.escape(name)), lore);
    }

    private static String pct(double value) {
        return new DecimalFormat("0.###", DecimalFormatSymbols.getInstance(Locale.US)).format(value) + "%";
    }

    private String money(double value) {
        Economy eco = plugin.economy();
        String text = eco != null ? eco.format(value) : String.format(Locale.ROOT, "%,.0f", value);
        return Text.escape(text);
    }
}
