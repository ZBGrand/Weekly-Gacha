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
import com.weeklygacha.util.Text;
import com.weeklygacha.util.TimeUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static com.weeklygacha.gui.GuiConfig.conds;
import static com.weeklygacha.gui.GuiConfig.vars;

/**
 * Logic các màn hình chest GUI: menu chính, spark, tỷ lệ, lịch sử, kết quả quay (có hoạt ảnh).
 * Toàn bộ chữ, vật phẩm, vị trí ô và âm thanh nằm trong gui.yml (xem {@link GuiConfig}); file này chỉ quyết định
 * điều kiện hiển thị và hành động khi bấm.
 */
public final class GachaMenu {

    private static final int PAGE_SIZE = 45;

    private final WeeklyGachaPlugin plugin;
    private final Map<UUID, String> selected = new ConcurrentHashMap<>();
    /** Người chơi đã tắt hoạt ảnh (chỉ trong phiên, mất khi thoát game/khởi động lại). */
    private final Set<UUID> animationOff = ConcurrentHashMap.newKeySet();

    public GachaMenu(WeeklyGachaPlugin plugin) {
        this.plugin = plugin;
    }

    private GuiConfig gui() {
        return plugin.gui();
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
        GuiConfig g = gui();
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
        Inventory inv = Bukkit.createInventory(holder, g.rows("main", 6) * 9, Text.mm(g.text("main.title", Map.of())));
        holder.bind(inv);
        holder.fill(g.filler());

        String remaining = TimeUtil.duration(bm.remainingMillis());

        // --- thanh chọn banner
        List<Banner> active = bm.activeBanners();
        int n = Math.min(active.size(), Math.max(1, g.integer("main.banner-tab.max", 7)));
        int start = g.integer("main.banner-tab.center-slot", 13) - (n - 1) / 2;
        for (int i = 0; i < n; i++) {
            Banner b = active.get(i);
            Set<String> c = new HashSet<>();
            flag(c, "limited", b.type() == BannerType.LIMITED);
            flag(c, "normal", b.type() != BannerType.LIMITED);
            flag(c, "limit", b.hasPullLimit());
            flag(c, "guarantee", b.alwaysRarity() != null);
            flag(c, "selected", b.id().equals(current.id()));
            ItemStack tab = g.decorate("main.banner-tab", new ItemStack(b.icon()), c, vars(
                    "banner", b.displayName(),
                    "remaining", remaining,
                    "cost", costText(b, b.singleCost()),
                    "max", String.valueOf(b.maxPulls()),
                    "rarity", b.alwaysRarity() != null ? b.alwaysRarity().tagged() : ""), Map.of());
            if (tab != null) holder.set(start + i, tab, (p, click) -> openMain(p, b));
        }

        // --- thông tin banner + vật phẩm nổi bật
        Set<String> info = new HashSet<>();
        flag(info, "limited", current.type() == BannerType.LIMITED);
        flag(info, "normal", current.type() != BannerType.LIMITED);
        flag(info, "override", bm.hasOverride());
        flag(info, "guarantee", current.alwaysRarity() != null);
        flag(info, "limit", current.hasPullLimit());
        ItemStack infoItem = g.decorate("main.banner-info", new ItemStack(current.icon()), info, vars(
                "banner", current.displayName(),
                "cycle", String.valueOf(bm.cycleNumber()),
                "remaining", remaining,
                "rarity", current.alwaysRarity() != null ? current.alwaysRarity().tagged() : "",
                "used", String.valueOf(Math.min(pd.getTotalPulls(), current.maxPulls())),
                "max", String.valueOf(current.maxPulls())), Map.of("description", current.description()));
        if (infoItem != null) holder.set(g.slot("main.banner-info", 22), infoItem, null);

        List<Reward> showcase = showcase(current);
        List<Integer> showSlots = g.intList("main.showcase-slots", List.of(21, 23));
        for (int i = 0; i < showSlots.size() && i < showcase.size(); i++) {
            ItemStack card = rewardCard(showcase.get(i), current, true, null, false);
            if (card != null) holder.set(showSlots.get(i), card, null);
        }

        // --- nút quay
        int usedPulls = pd.getTotalPulls();
        boolean exhausted = current.hasPullLimit() && current.pullsLeft(usedPulls) <= 0;
        String balance = Text.escape(plugin.costs().plainBalance(player, current));
        String max = String.valueOf(current.maxPulls());

        Map<String, String> singleVars = vars("cost", costText(current, current.singleCost()), "balance", balance, "max", max);
        if (exhausted) {
            put(holder, "main.pull-single-exhausted", g.item("main.pull-single-exhausted", Set.of(), singleVars, Map.of()), null);
        } else {
            put(holder, "main.pull-single", g.item("main.pull-single", Set.of(), singleVars, Map.of()),
                    (p, click) -> doPull(p, current, 1));
        }

        Rarity tg = current.spark().tenGuarantee();
        int saved = current.singleCost() * 10 - current.tenCost();
        Set<String> tenConds = new HashSet<>();
        flag(tenConds, "ten-guarantee", tg != null);
        flag(tenConds, "saving", saved > 0);
        flag(tenConds, "limit", current.hasPullLimit());
        Map<String, String> tenVars = vars(
                "cost", costText(current, current.tenCost()),
                "balance", balance,
                "saved", costText(current, Math.max(0, saved)),
                "ten-rarity", tg != null ? tg.tagged() : "",
                "max", max);
        if (current.allowsTen() && !exhausted && current.pullsLeft(usedPulls) >= 10) {
            put(holder, "main.pull-ten", g.item("main.pull-ten", tenConds, tenVars, Map.of()),
                    (p, click) -> doPull(p, current, 10));
        } else {
            put(holder, "main.pull-ten-unavailable", g.item("main.pull-ten-unavailable", tenConds, tenVars, Map.of()), null);
        }

        // --- spark
        SparkConfig sp = current.spark();
        boolean ready = sp.enabled() && pd.getSpark() >= sp.threshold();
        Map<String, List<String>> sparkBlocks = Map.of("spark-lines", sparkLines(current, pd));
        if (ready) {
            put(holder, "main.spark-ready", g.item("main.spark-ready", Set.of(), Map.of(), sparkBlocks),
                    (p, click) -> openSparkSelect(p, current));
        } else {
            put(holder, "main.spark-progress", g.item("main.spark-progress", Set.of(), Map.of(), sparkBlocks), null);
        }

        // --- tiện ích
        put(holder, "main.rates-button", g.item("main.rates-button"), (p, click) -> openRates(p, current, 0));
        String animPath = animationEnabled(player) ? "main.animation-on" : "main.animation-off";
        put(holder, animPath, g.item(animPath), (p, click) -> {
            toggleAnimation(p);
            openMain(p, current);
        });
        put(holder, "main.history-button", g.item("main.history-button"), (p, click) -> openHistory(p, 0));
        put(holder, "main.close-button", g.item("main.close-button"), (p, click) -> p.closeInventory());

        player.openInventory(inv);
    }

    private void doPull(Player player, Banner banner, int count) {
        PullResult result = plugin.gacha().pull(player, banner, count);
        if (result.success()) {
            openResult(player, banner, result, count);
        }
    }

    // ------------------------------------------------------------------ spark

    /** Các dòng mô tả spark — dùng cho GUI và lệnh /gacha spark (mẫu nằm trong gui.yml, mục spark-lines). */
    public List<String> sparkLines(Banner b, PlayerBannerData pd) {
        SparkConfig sp = b.spark();
        Set<String> c = new HashSet<>();
        flag(c, "enabled", sp.enabled());
        flag(c, "ready", sp.enabled() && pd.getSpark() >= sp.threshold());
        int threshold = Math.max(1, sp.threshold());
        return gui().lines("spark-lines", c, vars(
                "bar", gui().bar(Math.min(pd.getSpark(), threshold), threshold),
                "current", String.valueOf(pd.getSpark()),
                "threshold", String.valueOf(threshold),
                "left", String.valueOf(Math.max(0, threshold - pd.getSpark())),
                "exchanges", String.valueOf(pd.getSpark() / threshold),
                "rarity", sp.rarity().tagged(),
                "pity", String.valueOf(pd.getPity()),
                "total", String.valueOf(pd.getTotalPulls())), Map.of());
    }

    private List<String> sparkRules(Banner b) {
        SparkConfig sp = b.spark();
        Set<String> c = new HashSet<>();
        flag(c, "enabled", sp.enabled());
        flag(c, "featured", !b.featuredPool().isEmpty());
        flag(c, "ten-guarantee", sp.tenGuarantee() != null);
        return gui().lines("spark-rules", c, vars(
                "threshold", String.valueOf(sp.threshold()),
                "rarity", sp.rarity().tagged(),
                "ten-rarity", sp.tenGuarantee() != null ? sp.tenGuarantee().tagged() : ""), Map.of());
    }

    public void openSparkSelect(Player player, Banner banner) {
        GuiConfig g = gui();
        List<Reward> pool = banner.sparkPool();
        MenuHolder holder = new MenuHolder();
        Inventory inv = Bukkit.createInventory(holder, g.rows("spark-select", 4) * 9,
                Text.mm(g.text("spark-select.title", Map.of())));
        holder.bind(inv);
        holder.fill(g.filler());

        int shown = Math.min(pool.size(), Math.max(1, g.integer("spark-select.max", 7)));
        int start = g.integer("spark-select.center-slot", 13) - (shown - 1) / 2;
        for (int i = 0; i < shown; i++) {
            Reward r = pool.get(i);
            ItemStack card = rewardCard(r, banner, false, null, true);
            if (card != null) holder.set(start + i, card, (p, click) -> openSparkConfirm(p, banner, r));
        }
        put(holder, "spark-select.back", g.item("spark-select.back"), (p, click) -> openMain(p, banner));
        player.openInventory(inv);
    }

    private void openSparkConfirm(Player player, Banner banner, Reward reward) {
        GuiConfig g = gui();
        MenuHolder holder = new MenuHolder();
        Inventory inv = Bukkit.createInventory(holder, g.rows("spark-confirm", 3) * 9,
                Text.mm(g.text("spark-confirm.title", Map.of())));
        holder.bind(inv);
        holder.fill(g.filler());

        ItemStack card = rewardCard(reward, banner, false, null, false);
        if (card != null) holder.set(g.integer("spark-confirm.reward-slot", 13), card, null);

        Map<String, String> v = vars("threshold", String.valueOf(banner.spark().threshold()));
        put(holder, "spark-confirm.confirm", g.item("spark-confirm.confirm", Set.of(), v, Map.of()), (p, click) -> {
            if (plugin.gacha().exchange(p, banner, reward)) {
                gui().play(p, "sounds.spark-exchanged");
            }
            openMain(p, banner);
        });
        put(holder, "spark-confirm.cancel", g.item("spark-confirm.cancel"), (p, click) -> openSparkSelect(p, banner));
        player.openInventory(inv);
    }

    private List<Reward> showcase(Banner b) {
        List<Reward> pool = !b.featuredPool().isEmpty() ? b.featuredPool() : b.topPool();
        if (pool.isEmpty()) {
            pool = new ArrayList<>(b.rewards());
            pool.sort(Comparator.comparingInt((Reward r) -> r.rarity().ordinal()).reversed()
                    .thenComparingDouble(Reward::chance));
        }
        return pool;
    }

    // ------------------------------------------------------------------ tỷ lệ

    public void openRates(Player player, Banner banner, int page) {
        GuiConfig g = gui();
        List<Reward> sorted = new ArrayList<>(banner.rewards());
        sorted.sort(Comparator.comparingInt((Reward r) -> r.rarity().ordinal()).reversed()
                .thenComparingDouble(Reward::chance));
        int pages = Math.max(1, (sorted.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int pg = Math.max(0, Math.min(page, pages - 1));

        MenuHolder holder = new MenuHolder();
        Inventory inv = Bukkit.createInventory(holder, 54,
                Text.mm(g.text("rates.title", vars("banner", banner.displayName()))));
        holder.bind(inv);
        holder.fill(g.filler());

        int from = pg * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && from + i < sorted.size(); i++) {
            ItemStack card = rewardCard(sorted.get(from + i), banner, true, null, false);
            if (card != null) holder.set(i, card, null);
        }

        navigation(holder, pg > 0, pg < pages - 1, conds("paged"),
                vars("page", String.valueOf(pg + 1), "pages", String.valueOf(pages)),
                p -> openMain(p, banner), p -> openRates(p, banner, pg - 1), p -> openRates(p, banner, pg + 1));
        put(holder, "rates.rules-button", g.item("rates.rules-button", Set.of(), Map.of(),
                Map.of("spark-rules", sparkRules(banner))), null);
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
        GuiConfig g = gui();
        boolean hasNext = entries.size() > PAGE_SIZE;
        BannerManager bm = plugin.banners();

        MenuHolder holder = new MenuHolder();
        Inventory inv = Bukkit.createInventory(holder, 54,
                Text.mm(g.text("history.title", vars("page", String.valueOf(page + 1)))));
        holder.bind(inv);
        holder.fill(g.filler());

        for (int i = 0; i < Math.min(PAGE_SIZE, entries.size()); i++) {
            HistoryEntry e = entries.get(i);
            Banner b = bm.get(e.bannerId());
            Reward r = b != null ? b.find(e.rewardId()) : null;
            ItemStack base = r != null ? plugin.rewards().icon(r) : new ItemStack(Material.PAPER);
            Set<String> c = new HashSet<>();
            flag(c, "spark", e.pityAtPull() <= 0);
            ItemStack entry = g.decorate("history.entry", base, c, vars(
                    "name", Text.escape(e.rewardName()),
                    "rarity", e.rarity().tagged(),
                    "rarity-color", e.rarity().color(),
                    "banner", b != null ? b.displayName() : "<gray>" + Text.escape(e.bannerId()),
                    "cycle", String.valueOf(e.cycle()),
                    "pity", String.valueOf(e.pityAtPull()),
                    "time", Text.escape(TimeUtil.date(e.timestamp(), bm.zone()))), Map.of());
            if (entry != null) holder.set(i, entry, null);
        }

        Banner back = selectedBanner(player);
        navigation(holder, page > 0, hasNext, Set.of(), Map.of(),
                p -> openMain(p, back), p -> openHistory(p, page - 1), p -> openHistory(p, page + 1));
        if (entries.isEmpty()) {
            put(holder, "history.empty", g.item("history.empty"), null);
        }
        player.openInventory(inv);
    }

    // ------------------------------------------------------------------ kết quả quay (+ hoạt ảnh)

    /**
     * Hiển thị kết quả quay. Quà chỉ được trao khi hoạt ảnh kết thúc (hoặc ngay nếu hoạt ảnh bị tắt).
     */
    public void openResult(Player player, Banner banner, PullResult result, int count) {
        GuiConfig g = gui();
        List<PullEntry> entries = result.entries();
        MenuHolder holder = new MenuHolder();
        Inventory inv = Bukkit.createInventory(holder, g.rows("result", 4) * 9,
                Text.mm(g.text("result.title", vars("count", String.valueOf(count)))));
        holder.bind(inv);
        holder.fill(g.background());

        List<Integer> slotList = entries.size() == 1
                ? g.intList("result.slots-single", List.of(13))
                : g.intList("result.slots-multi", List.of(11, 12, 13, 14, 15, 20, 21, 22, 23, 24));
        int[] slots = slotList.stream().mapToInt(Integer::intValue).toArray();

        if (!animationEnabled(player)) {
            for (int i = 0; i < entries.size() && i < slots.length; i++) {
                holder.set(slots[i], cardFor(entries.get(i), banner), null);
            }
            showFinal(holder, player, banner, result, count);
            player.openInventory(inv);
            plugin.gacha().deliver(player, result);
            plugin.gacha().announce(player, banner, result);
            return;
        }

        GachaAnimation animation = new GachaAnimation(plugin, this, player, holder, banner, result, count, slots);
        animation.prepare();
        put(holder, "result.skip", g.item("result.skip"), (p, click) -> animation.skip());
        player.openInventory(inv);
        animation.runTaskTimer(plugin, 10L, GachaAnimation.PERIOD);
    }

    /** Hoàn thiện màn hình kết quả: tóm tắt, nút quay lại / quay tiếp. */
    void showFinal(MenuHolder holder, Player player, Banner banner, PullResult result, int count) {
        GuiConfig g = gui();
        Map<Rarity, Integer> tally = new EnumMap<>(Rarity.class);
        Rarity best = Rarity.COMMON;
        for (PullEntry e : result.entries()) {
            tally.merge(e.reward().rarity(), 1, Integer::sum);
            if (e.reward().rarity().ordinal() > best.ordinal()) best = e.reward().rarity();
        }

        List<String> rarityLines = new ArrayList<>();
        String lineTemplate = g.string("result.rarity-line", "{rarity}<gray>: <white>{count}");
        for (Rarity r : Rarity.values()) {
            int c = tally.getOrDefault(r, 0);
            if (c > 0) rarityLines.add(g.fmt(lineTemplate, vars("rarity", r.tagged(), "count", String.valueOf(c))));
        }
        put(holder, "result.summary", g.item("result.summary", Set.of(), vars(
                "count", String.valueOf(count),
                "banner-plain", Text.escape(Text.plain(Text.mm(banner.displayName())))),
                Map.of("rarity-lines", rarityLines)), null);

        // ô của nút "Bỏ qua" quay về nền khi hoạt ảnh kết thúc
        holder.set(g.slot("result.skip", 31), g.background(), null);

        put(holder, "result.back", g.item("result.back"), (p, click) -> openMain(p, banner));

        PlayerData pdata = plugin.players().get(player.getUniqueId());
        int usedNow = pdata == null ? 0 : pdata.get(banner.id()).getTotalPulls();
        if (banner.pullsLeft(usedNow) >= count) { // banner giới hạn lượt: hết lượt thì không còn nút quay tiếp
            String path = count == 10 ? "result.again-ten" : "result.again-single";
            put(holder, path, g.item(path, Set.of(), vars(
                    "count", String.valueOf(count),
                    "cost", costText(banner, banner.costFor(count)),
                    "balance", Text.escape(plugin.costs().plainBalance(player, banner))), Map.of()),
                    (p, click) -> doPull(p, banner, count));
        }

        Rarity high = Rarity.parse(g.string("sounds.result-high-rarity", "EPIC"), Rarity.EPIC);
        g.play(player, best.ordinal() >= high.ordinal() ? "sounds.result-high" : "sounds.result-normal");
    }

    /** Thẻ hiển thị một lượt quay (vật phẩm + độ hiếm + số lượt). */
    ItemStack cardFor(PullEntry e, Banner banner) {
        return rewardCard(e.reward(), banner, false, e.pityAtPull(), false);
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

    /** Đặt vật phẩm vào ô cấu hình trong gui.yml ({@code <path>.slot}); bỏ qua nếu vật phẩm bị tắt. */
    private void put(MenuHolder holder, String path, ItemStack item, MenuAction action) {
        if (item == null) return;
        holder.set(gui().slot(path, -1), item, action);
    }

    private void navigation(MenuHolder holder, boolean hasPrev, boolean hasNext, Set<String> conds,
                            Map<String, String> vars, PlayerAction back, PlayerAction prev, PlayerAction next) {
        GuiConfig g = gui();
        if (hasPrev) put(holder, "nav.prev", g.item("nav.prev"), (p, click) -> prev.run(p));
        put(holder, "nav.back", g.item("nav.back", conds, vars, Map.of()), (p, click) -> back.run(p));
        if (hasNext) put(holder, "nav.next", g.item("nav.next"), (p, click) -> next.run(p));
    }

    private static void flag(Set<String> set, String name, boolean on) {
        if (on) set.add(name);
    }

    /**
     * Thẻ vật phẩm theo mẫu reward-card trong gui.yml.
     *
     * @param pity   số lượt kể từ lần hiếm trước (null = không hiện dòng này)
     * @param choose đang ở màn hình chọn spark
     */
    private ItemStack rewardCard(Reward r, Banner banner, boolean showRate, Integer pity, boolean choose) {
        Set<String> c = new HashSet<>();
        flag(c, "amount", r.amount() > 1);
        flag(c, "rate", showRate);
        flag(c, "featured", r.featured());
        flag(c, "pity", pity != null);
        flag(c, "choose", choose);
        return gui().decorate("reward-card", plugin.rewards().icon(r), c, vars(
                "name", Text.escape(plugin.rewards().nameOf(r)),
                "rarity", r.rarity().tagged(),
                "rarity-color", r.rarity().color(),
                "amount", String.valueOf(r.amount()),
                "rate", pct(banner.ratePercent(r)),
                "pity", pity == null ? "" : String.valueOf(pity)), Map.of());
    }

    private String pct(double value) {
        String pattern = gui().string("format.rate", "0.###");
        try {
            return new DecimalFormat(pattern, DecimalFormatSymbols.getInstance(Locale.US)).format(value);
        } catch (IllegalArgumentException e) {
            return String.valueOf(value);
        }
    }

    /** Chi phí dạng "100 điểm" / "3x Chìa khoá", đã escape để chèn vào MiniMessage. */
    private String costText(Banner banner, int amount) {
        return Text.escape(plugin.costs().plain(banner, amount));
    }
}
