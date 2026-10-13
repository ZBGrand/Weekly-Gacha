package com.weeklygacha.gui;

import com.weeklygacha.WeeklyGachaPlugin;
import com.weeklygacha.model.Banner;
import com.weeklygacha.model.BannerType;
import com.weeklygacha.model.HistoryEntry;
import com.weeklygacha.model.PityConfig;
import com.weeklygacha.model.PlayerBannerData;
import com.weeklygacha.model.PlayerData;
import com.weeklygacha.model.PullEntry;
import com.weeklygacha.model.PullResult;
import com.weeklygacha.model.Rarity;
import com.weeklygacha.model.Reward;
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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Toàn bộ giao diện chest GUI: menu chính, tỷ lệ, lịch sử, kết quả quay.
 * Mọi chuỗi hiển thị (MiniMessage) của GUI nằm trong file này.
 */
public final class GachaMenu {

    private static final int PAGE_SIZE = 45;
    private static final String TITLE_MAIN = "<dark_gray>✦ <gradient:#ffb347:#ffcc33>Gacha Hàng Tuần</gradient> <dark_gray>✦";

    private final WeeklyGachaPlugin plugin;
    private final Map<UUID, String> selected = new ConcurrentHashMap<>();

    public GachaMenu(WeeklyGachaPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ chọn banner

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
        double saved = current.singleCost() * 10 - current.tenCost();
        if (saved > 0) tenLore.add("<gray>Tiết kiệm: <green>" + money(saved));
        tenLore.add("");
        tenLore.add("<yellow>Nhấn để quay");
        holder.set(33, Items.of(Material.DIAMOND, 10, "<aqua><bold>Quay x10", tenLore), (p, c) -> doPull(p, current, 10));

        holder.set(31, Items.of(Material.EXPERIENCE_BOTTLE, 1, "<yellow><bold>Bảo hiểm (Pity)", pityLines(current, pd)), null);

        // --- hàng 5: tiện ích
        holder.set(38, Items.of(Material.BOOK, 1, "<white><bold>Tỷ lệ vật phẩm",
                List.of("<gray>Xem tỷ lệ riêng của từng vật phẩm.")), (p, c) -> openRates(p, current, 0));
        holder.set(42, Items.of(Material.WRITABLE_BOOK, 1, "<white><bold>Lịch sử quay",
                List.of("<gray>Các lượt quay gần đây của bạn.")), (p, c) -> openHistory(p, 0));
        holder.set(49, Items.of(Material.BARRIER, 1, "<red><bold>Đóng", List.of()), (p, c) -> p.closeInventory());

        player.openInventory(inv);
    }

    private void doPull(Player player, Banner banner, int count) {
        PullResult result = plugin.gacha().pull(player, banner, count);
        if (result.success()) {
            openResult(player, banner, result.entries(), count);
        }
    }

    /** Dòng mô tả pity — dùng cho GUI và lệnh /gacha pity. */
    public List<String> pityLines(Banner b, PlayerBannerData pd) {
        PityConfig pc = b.pity();
        List<String> l = new ArrayList<>();
        if (!pc.enabled()) {
            l.add("<gray>Banner này không có bảo hiểm.");
            l.add("<gray>Tổng lượt đã quay: <white>" + pd.getTotalPulls());
            return l;
        }
        int left = Math.max(0, pc.hard() - pd.getPity());
        l.add("<gray>Đã quay từ lần " + pc.rarity().tagged() + "<gray> gần nhất: <white>" + pd.getPity() + "<gray>/<white>" + pc.hard());
        l.add("<gray>Chắc chắn ra " + pc.rarity().tagged() + "<gray> trong tối đa <white>" + left + "<gray> lượt nữa.");
        l.add("<gray>Tỷ lệ tăng dần từ lượt thứ <white>" + pc.softStart() + "<gray>.");
        if (b.hasGuarantee()) {
            l.add("<gray>Lần " + pc.rarity().tagged() + "<gray> tới chắc chắn là vật phẩm nổi bật: "
                    + (pd.isGuaranteed() ? "<green>Có" : "<red>Chưa"));
        }
        l.add("<gray>Tổng lượt đã quay: <white>" + pd.getTotalPulls());
        return l;
    }

    private List<Reward> showcase(Banner b) {
        List<Reward> pool = !b.featuredPool().isEmpty() ? b.featuredPool() : b.pityPool();
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
        holder.set(53, Items.of(Material.PAPER, 1, "<yellow><bold>Quy tắc bảo hiểm", pityRules(banner)), null);
        player.openInventory(inv);
    }

    private List<String> pityRules(Banner b) {
        PityConfig pc = b.pity();
        if (!pc.enabled()) return List.of("<gray>Banner này không có bảo hiểm.");
        List<String> l = new ArrayList<>();
        l.add("<gray>Độ hiếm được bảo đảm: " + pc.rarity().tagged());
        l.add("<gray>Chắc chắn ra sau tối đa <white>" + pc.hard() + "<gray> lượt.");
        l.add("<gray>Từ lượt <white>" + pc.softStart() + "<gray> tỷ lệ tăng dần.");
        if (b.hasGuarantee()) {
            l.add("<gray>Nếu lần trước không ra vật phẩm nổi bật,");
            l.add("<gray>lần kế tiếp chắc chắn ra vật phẩm nổi bật.");
        }
        l.add("<dark_gray>Pity tính riêng cho từng banner.");
        return l;
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
            lore.add(Text.mm("<gray>Ra sau: <white>" + e.pityAtPull() + "<gray> lượt"));
            lore.add(Text.mm("<dark_gray>" + TimeUtil.date(e.timestamp(), bm.zone())));
            holder.set(i, Items.decorate(base, Text.mm(e.rarity().color() + Text.escape(e.rewardName())), lore), null);
        }

        Banner back = selectedBanner(player);
        MenuAction prev = page > 0 ? (p, c) -> openHistory(p, page - 1) : null;
        MenuAction next = hasNext ? (p, c) -> openHistory(p, page + 1) : null;
        if (prev != null) holder.set(48, Items.of(Material.ARROW, 1, "<yellow>◀ Trang trước", List.of()), prev);
        if (next != null) holder.set(50, Items.of(Material.ARROW, 1, "<yellow>Trang sau ▶", List.of()), next);
        holder.set(49, Items.of(Material.OAK_DOOR, 1, "<yellow>Quay lại", List.of()), (p, c) -> openMain(p, back));
        if (entries.isEmpty()) {
            holder.set(22, Items.of(Material.BARRIER, 1, "<gray>Chưa có lượt quay nào", List.of()), null);
        }
        player.openInventory(inv);
    }

    // ------------------------------------------------------------------ kết quả

    public void openResult(Player player, Banner banner, List<PullEntry> entries, int count) {
        MenuHolder holder = new MenuHolder();
        Inventory inv = Bukkit.createInventory(holder, 36, Text.mm("<dark_gray>Kết quả quay x" + count));
        holder.bind(inv);
        holder.fill(Items.filler());

        int[] single = {13};
        int[] multi = {11, 12, 13, 14, 15, 20, 21, 22, 23, 24};
        int[] slots = entries.size() == 1 ? single : multi;

        Map<Rarity, Integer> tally = new EnumMap<>(Rarity.class);
        Rarity best = Rarity.COMMON;
        for (int i = 0; i < entries.size() && i < slots.length; i++) {
            PullEntry e = entries.get(i);
            tally.merge(e.reward().rarity(), 1, Integer::sum);
            if (e.reward().rarity().ordinal() > best.ordinal()) best = e.reward().rarity();
            holder.set(slots[i], rewardDisplay(e.reward(), banner, false,
                    List.of("<gray>Ra sau: <white>" + e.pityAtPull() + "<gray> lượt")), null);
        }

        List<String> summary = new ArrayList<>();
        summary.add("<gray>Banner: <white>" + Text.plain(Text.mm(banner.displayName())));
        for (Rarity r : Rarity.values()) {
            int c = tally.getOrDefault(r, 0);
            if (c > 0) summary.add(r.tagged() + "<gray>: <white>" + c);
        }
        holder.set(4, Items.of(Material.NETHER_STAR, 1, "<gold><bold>Kết quả quay x" + count, summary), null);

        holder.set(30, Items.of(Material.OAK_DOOR, 1, "<yellow>Quay lại", List.of()), (p, c) -> openMain(p, banner));
        double cost = count == 10 ? banner.tenCost() : banner.singleCost() * count;
        holder.set(32, Items.of(count == 10 ? Material.DIAMOND : Material.EMERALD, 1, "<green><bold>Quay tiếp x" + count,
                List.of("<gray>Chi phí: <gold>" + money(cost))), (p, c) -> doPull(p, banner, count));

        player.openInventory(inv);
        Sound sound = best.ordinal() >= Rarity.EPIC.ordinal()
                ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.ENTITY_PLAYER_LEVELUP;
        player.playSound(player.getLocation(), sound, 1f, 1f);
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
