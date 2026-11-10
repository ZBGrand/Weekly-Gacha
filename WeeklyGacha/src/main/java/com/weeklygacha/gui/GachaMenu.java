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

    private static final List<Integer> DEFAULT_PAGE = java.util.stream.IntStream.range(0, 45).boxed().toList();

    private final WeeklyGachaPlugin plugin;
    private final Map<UUID, String> selected = new ConcurrentHashMap<>();
    /** Người chơi đã tắt hoạt ảnh (chỉ trong phiên, mất khi thoát game/khởi động lại). */
    private final Set<UUID> animationOff = ConcurrentHashMap.newKeySet();

    private long tickStep;

    public GachaMenu(WeeklyGachaPlugin plugin) {
        this.plugin = plugin;
        // decor động (viền chạy, nhấp nháy...) của các menu đang mở: cập nhật mỗi 2 tick
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickMenus, 2L, 2L);
    }

    private void tickMenus() {
        tickStep++;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof MenuHolder h && h.animated()) {
                h.tick(tickStep);
            }
        }
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
        String theme = g.theme(current.id());
        Inventory inv = Bukkit.createInventory(holder, g.rows("main", 6) * 9,
                Text.mm(g.text("main.title", vars("banner", current.displayName()))));
        holder.bind(inv);
        g.applyLayout(holder, "main", theme, g.filler());

        String remaining = TimeUtil.duration(bm.remainingMillis());

        // --- thanh chọn banner
        List<Banner> active = bm.activeBanners();
        int n = Math.min(active.size(), Math.max(1, g.integer("main.banner-tab.max", 7)));
        int[] tabSlots = tabSlots(n);
        int markerOffset = g.integer("main.banner-tab.marker.offset", -9);
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
            if (tab != null) holder.set(tabSlots[i], tab, (p, click) -> openMain(p, b));
        }
        for (int i = 0; i < n; i++) {   // dấu chỉ banner đang chọn (đặt sau để không đè lên tab khác)
            if (!active.get(i).id().equals(current.id())) continue;
            ItemStack marker = g.item("main.banner-tab.marker", Set.of(), vars("banner", current.displayName()), Map.of());
            if (marker != null && g.section("main.banner-tab.marker") != null) {
                holder.setIfFree(tabSlots[i] + markerOffset, marker, null);
            }
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
            ItemStack card = rewardCard(showcase.get(i), current, true, null, false, "showcase");
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

        // --- hồ sơ người chơi (đầu skin)
        if (g.section("main.profile") != null) {
            SparkConfig spk = current.spark();
            Set<String> pc = new HashSet<>();
            flag(pc, "spark", spk.enabled());
            flag(pc, "limit", current.hasPullLimit());
            ItemStack head = g.item("main.profile", pc, vars(
                    "player", Text.escape(player.getName()),
                    "balance", balance,
                    "lunacy", plugin.lunacy().plain(Math.max(0, plugin.lunacy().balance(player.getUniqueId()))),
                    "total", String.valueOf(pd.getTotalPulls()),
                    "spark", String.valueOf(pd.getSpark()),
                    "threshold", String.valueOf(spk.threshold()),
                    "pity", String.valueOf(pd.getPity()),
                    "used", String.valueOf(Math.min(pd.getTotalPulls(), current.maxPulls())),
                    "max", max), Map.of());
            if (head != null) {
                if (head.getItemMeta() instanceof org.bukkit.inventory.meta.SkullMeta skull) {
                    skull.setOwningPlayer(player);
                    head.setItemMeta(skull);
                }
                put(holder, "main.profile", head, null);
            }
        }

        // --- tiện ích
        put(holder, "main.rates-button", g.item("main.rates-button"), (p, click) -> openRates(p, current, 0));
        String animPath = animationEnabled(player) ? "main.animation-on" : "main.animation-off";
        put(holder, animPath, g.item(animPath), (p, click) -> {
            toggleAnimation(p);
            openMain(p, current);
        });
        put(holder, "main.history-button", g.item("main.history-button"), (p, click) -> openHistory(p, 0));
        if (plugin.lunacy().shopEnabled()) {
            put(holder, "main.shop-button", g.item("main.shop-button", Set.of(), vars(
                    "lunacy", plugin.lunacy().plain(Math.max(0, plugin.lunacy().balance(player.getUniqueId())))), Map.of()),
                    (p, click) -> openShop(p, current));
        }
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
                Text.mm(g.text("spark-select.title", vars("banner", banner.displayName()))));
        holder.bind(inv);
        g.applyLayout(holder, "spark-select", g.theme(banner.id()), g.filler());

        int shown = Math.min(pool.size(), Math.max(1, g.integer("spark-select.max", 7)));
        int start = g.integer("spark-select.center-slot", 13) - (shown - 1) / 2;
        for (int i = 0; i < shown; i++) {
            Reward r = pool.get(i);
            ItemStack card = rewardCard(r, banner, false, null, true, "spark");
            if (card != null) holder.set(start + i, card, (p, click) -> openSparkConfirm(p, banner, r));
        }
        put(holder, "spark-select.back", g.item("spark-select.back"), (p, click) -> openMain(p, banner));
        player.openInventory(inv);
    }

    private void openSparkConfirm(Player player, Banner banner, Reward reward) {
        GuiConfig g = gui();
        MenuHolder holder = new MenuHolder();
        Inventory inv = Bukkit.createInventory(holder, g.rows("spark-confirm", 3) * 9,
                Text.mm(g.text("spark-confirm.title", vars("banner", banner.displayName()))));
        holder.bind(inv);
        g.applyLayout(holder, "spark-confirm", g.theme(banner.id()), g.filler());

        ItemStack card = rewardCard(reward, banner, false, null, false, "spark-confirm");
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

    // ------------------------------------------------------------------ cửa hàng Lunacy

    /** Cửa hàng đổi PlayerPoints lấy Lunacy. {@code back} = banner để quay lại menu chính (có thể null). */
    public void openShop(Player player, Banner back) {
        GuiConfig g = gui();
        com.weeklygacha.service.LunacyService lu = plugin.lunacy();
        if (!lu.shopEnabled()) {
            plugin.messages().send(player, "lunacy-shop-disabled");
            return;
        }
        MenuHolder holder = new MenuHolder();
        Inventory inv = Bukkit.createInventory(holder, g.rows("shop", 5) * 9, Text.mm(g.text("shop.title", Map.of())));
        holder.bind(inv);
        g.applyLayout(holder, "shop", back != null ? g.theme(back.id()) : null, g.filler());

        long balance = Math.max(0, lu.balance(player.getUniqueId()));
        int points = lu.points(player);
        Map<String, String> wallet = vars(
                "lunacy", lu.plain(balance),
                "points", points < 0 ? "-" : g.points(points),
                "player", Text.escape(player.getName()));
        Set<String> wc = new HashSet<>();
        flag(wc, "playerpoints", points >= 0);
        ItemStack wal = g.item("shop.balance", wc, wallet, Map.of());
        if (wal != null && wal.getItemMeta() instanceof org.bukkit.inventory.meta.SkullMeta skull) {
            skull.setOwningPlayer(player);
            wal.setItemMeta(skull);
        }
        put(holder, "shop.balance", wal, null);

        List<Integer> auto = g.slotList("shop.package-slots", List.of(20, 21, 22, 23, 24));
        int i = 0;
        for (com.weeklygacha.service.LunacyService.ShopPackage pk : lu.packages()) {
            int slot = pk.slot() >= 0 ? pk.slot() : (i < auto.size() ? auto.get(i) : -1);
            i++;
            ItemStack icon = packageItem(pk, points);
            if (icon != null) holder.set(slot, icon, (p, click) -> openShopConfirm(p, pk, back));
        }

        if (lu.customEnabled()) {
            put(holder, "shop.custom", g.item("shop.custom", Set.of(), vars(
                    "rate", g.points((long) Math.ceil(lu.pointsPerLunacy() * 100)),
                    "per", lu.plain(100),
                    "min", lu.number(lu.customMin()),
                    "max", lu.number(lu.customMax())), Map.of()), null);
        }
        put(holder, "shop.back", g.item("shop.back"), (p, click) -> openMain(p, back));
        player.openInventory(inv);
    }

    private ItemStack packageItem(com.weeklygacha.service.LunacyService.ShopPackage pk, int points) {
        GuiConfig g = gui();
        com.weeklygacha.service.LunacyService lu = plugin.lunacy();
        Set<String> c = new HashSet<>();
        flag(c, "bonus", pk.bonus() > 0);
        flag(c, "affordable", points >= pk.price());
        flag(c, "playerpoints", points >= 0);
        Map<String, String> v = vars(
                "id", pk.id(),
                "amount", lu.plain(pk.amount()),
                "bonus", lu.plain(pk.bonus()),
                "total", lu.plain(pk.total()),
                "price", g.points(pk.price()),
                "points", points < 0 ? "-" : g.points(points));
        String name = pk.name() != null ? g.fmt(pk.name(), v) : g.text("shop.package.name", v);
        List<String> lore = g.lines("shop.package.lore", c, v, Map.of("extra", pk.lore().stream().map(l -> g.fmt(l, v)).toList()));
        ItemStack item = com.weeklygacha.util.Items.of(pk.material(), 1, name, lore);
        if (pk.glow()) com.weeklygacha.util.Items.glow(item);
        return item;
    }

    private void openShopConfirm(Player player, com.weeklygacha.service.LunacyService.ShopPackage pk, Banner back) {
        GuiConfig g = gui();
        com.weeklygacha.service.LunacyService lu = plugin.lunacy();
        MenuHolder holder = new MenuHolder();
        Inventory inv = Bukkit.createInventory(holder, g.rows("shop-confirm", 3) * 9,
                Text.mm(g.text("shop-confirm.title", Map.of())));
        holder.bind(inv);
        g.applyLayout(holder, "shop-confirm", back != null ? g.theme(back.id()) : null, g.filler());

        int points = lu.points(player);
        ItemStack icon = packageItem(pk, points);
        if (icon != null) holder.set(g.integer("shop-confirm.package-slot", 13), icon, null);
        Map<String, String> v = vars("total", lu.plain(pk.total()), "price", g.points(pk.price()));
        put(holder, "shop-confirm.confirm", g.item("shop-confirm.confirm", Set.of(), v, Map.of()), (p, click) -> {
            buy(p, pk.total(), pk.price(), "BUY:" + pk.id());
            openShop(p, back);
        });
        put(holder, "shop-confirm.cancel", g.item("shop-confirm.cancel"), (p, click) -> openShop(p, back));
        player.openInventory(inv);
    }

    /** Mua Lunacy và báo kết quả cho người chơi (dùng chung cho GUI và lệnh). */
    public boolean buy(Player player, long lunacy, int price, String reason) {
        com.weeklygacha.service.LunacyService lu = plugin.lunacy();
        com.weeklygacha.service.LunacyService.BuyResult r = lu.buy(player, lunacy, price, reason);
        switch (r) {
            case OK -> {
                plugin.messages().send(player, "lunacy-bought",
                        com.weeklygacha.service.MessageService.ph("amount", lu.plain(lunacy)),
                        com.weeklygacha.service.MessageService.ph("price", gui().points(price)),
                        com.weeklygacha.service.MessageService.ph("balance",
                                lu.plain(Math.max(0, lu.balance(player.getUniqueId())))));
                gui().play(player, "sounds.lunacy-bought");
            }
            case DISABLED -> plugin.messages().send(player, "lunacy-shop-disabled");
            case NOT_LOADED -> plugin.messages().send(player, "data-loading");
            case NO_PLAYERPOINTS -> plugin.messages().send(player, "cost-provider-missing",
                    com.weeklygacha.service.MessageService.ph("plugin", "PlayerPoints"));
            case NOT_ENOUGH_POINTS -> plugin.messages().send(player, "lunacy-not-enough-points",
                    com.weeklygacha.service.MessageService.ph("price", gui().points(price)),
                    com.weeklygacha.service.MessageService.ph("points", gui().points(Math.max(0, lu.points(player)))));
            case FAILED -> plugin.messages().send(player, "charge-failed",
                    com.weeklygacha.service.MessageService.ph("cost", gui().points(price)));
        }
        return r == com.weeklygacha.service.LunacyService.BuyResult.OK;
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
        List<Integer> area = g.slotList("rates.item-slots", DEFAULT_PAGE);
        int size = area.size();
        int pages = Math.max(1, (sorted.size() + size - 1) / size);
        int pg = Math.max(0, Math.min(page, pages - 1));

        MenuHolder holder = new MenuHolder();
        Inventory inv = Bukkit.createInventory(holder, 54,
                Text.mm(g.text("rates.title", vars("banner", banner.displayName(),
                        "page", String.valueOf(pg + 1), "pages", String.valueOf(pages)))));
        holder.bind(inv);
        g.applyLayout(holder, "rates", g.theme(banner.id()), g.filler());

        int from = pg * size;
        for (int i = 0; i < size && from + i < sorted.size(); i++) {
            ItemStack card = rewardCard(sorted.get(from + i), banner, true, null, false, "rates");
            if (card != null) holder.set(area.get(i), card, null);
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
        int size = gui().slotList("history.item-slots", DEFAULT_PAGE).size();
        plugin.database().history(uuid, size + 1, pg * size).whenComplete((list, error) ->
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
        List<Integer> area = g.slotList("history.item-slots", DEFAULT_PAGE);
        int size = area.size();
        boolean hasNext = entries.size() > size;
        BannerManager bm = plugin.banners();
        Banner back = selectedBanner(player);

        MenuHolder holder = new MenuHolder();
        Inventory inv = Bukkit.createInventory(holder, 54,
                Text.mm(g.text("history.title", vars("page", String.valueOf(page + 1)))));
        holder.bind(inv);
        g.applyLayout(holder, "history", back != null ? g.theme(back.id()) : null, g.filler());

        for (int i = 0; i < Math.min(size, entries.size()); i++) {
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
            if (entry != null) holder.set(area.get(i), entry, null);
        }

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
                Text.mm(g.text("result.title", vars("count", String.valueOf(count), "banner", banner.displayName()))));
        holder.bind(inv);
        g.applyLayout(holder, "result", g.theme(banner.id()), g.background());

        List<Integer> slotList = entries.size() == 1
                ? g.slotList("result.slots-single", List.of(13))
                : g.slotList("result.slots-multi", List.of(11, 12, 13, 14, 15, 20, 21, 22, 23, 24));
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
        holder.restore(g.slot("result.skip", 31));

        // viền đổi màu theo độ hiếm cao nhất vừa quay được
        String tintSymbols = g.string("result.tint.symbols", "");
        if (!tintSymbols.isEmpty() && best.ordinal() >= Rarity.parse(g.string("result.tint.min-rarity", "COMMON"),
                Rarity.COMMON).ordinal()) {
            List<String> pattern = g.stringList("result.tint.frames");
            if (pattern.isEmpty()) pattern = List.of("RARITY");
            List<ItemStack> frames = g.frames(g.section("result.tint"), pattern, best, "result.tint");
            for (char c : tintSymbols.toCharArray()) {
                holder.retint(c, frames, g.string("result.tint.mode", "chase").toLowerCase(Locale.ROOT),
                        g.integer("result.tint.speed", 2));
            }
        }

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
        return rewardCard(e.reward(), banner, false, e.pityAtPull(), false, "result");
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

    /**
     * Ô của các tab banner: {@code banner-tab.slots} nếu có, nếu không thì căn giữa quanh {@code center-slot}
     * với khoảng cách {@code spacing} (2 = chừa 1 ô trống giữa các tab).
     */
    private int[] tabSlots(int n) {
        GuiConfig g = gui();
        List<Integer> fixed = g.slotList("main.banner-tab.slots", List.of());
        int[] out = new int[n];
        if (fixed.size() >= n) {
            for (int i = 0; i < n; i++) out[i] = fixed.get(i);
            return out;
        }
        int center = g.integer("main.banner-tab.center-slot", 13);
        int spacing = Math.max(1, g.integer("main.banner-tab.spacing", 1));
        if (spacing * (n - 1) > 8) spacing = 1;
        int start = center - (spacing * (n - 1)) / 2;
        for (int i = 0; i < n; i++) out[i] = start + i * spacing;
        return out;
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
    /**
     * @param screen nơi hiện thẻ, cũng là một điều kiện trong gui.yml:
     *               showcase (menu chính), rates, spark, spark-confirm, result
     */
    private ItemStack rewardCard(Reward r, Banner banner, boolean showRate, Integer pity, boolean choose, String screen) {
        Set<String> c = new HashSet<>();
        flag(c, "amount", r.amount() > 1);
        flag(c, "rate", showRate);
        flag(c, "featured", r.featured());
        flag(c, "pity", pity != null);
        flag(c, "choose", choose);
        c.add(screen);
        ItemStack icon = plugin.rewards().icon(r);
        return gui().decorate("reward-card", icon, c, vars(
                "name", Text.escape(plugin.rewards().nameOf(r)),
                "rarity", r.rarity().tagged(),
                "rarity-color", r.rarity().color(),
                "amount", String.valueOf(r.amount()),
                "rate", pct(banner.ratePercent(r)),
                "pity", pity == null ? "" : String.valueOf(pity)), Map.of("item-lore", itemLore(icon)));
    }

    /**
     * Lore gốc của vật phẩm (vd chỉ số vũ khí MMOItems) để chèn vào thẻ bằng khối {item-lore}.
     * Giới hạn số dòng: reward-card.item-lore.max-lines (0 = không giới hạn).
     */
    private List<String> itemLore(ItemStack icon) {
        org.bukkit.inventory.meta.ItemMeta meta = icon == null ? null : icon.getItemMeta();
        List<net.kyori.adventure.text.Component> lore = meta == null ? null : meta.lore();
        if (lore == null || lore.isEmpty()) return List.of();
        GuiConfig g = gui();
        net.kyori.adventure.text.minimessage.MiniMessage mm = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage();
        List<String> out = new ArrayList<>();
        for (net.kyori.adventure.text.Component line : lore) out.add(mm.serialize(line));
        if (g.bool("reward-card.item-lore.trim-empty", true)) {
            while (!out.isEmpty() && mm.stripTags(out.get(0)).isBlank()) out.remove(0);
            while (!out.isEmpty() && mm.stripTags(out.get(out.size() - 1)).isBlank()) out.remove(out.size() - 1);
        }
        int max = g.integer("reward-card.item-lore.max-lines", 0);
        if (max > 0 && out.size() > max) {
            int hidden = out.size() - max;
            out = new ArrayList<>(out.subList(0, max));
            String more = g.string("reward-card.item-lore.more", "");
            if (!more.isEmpty()) out.add(g.fmt(more, vars("count", String.valueOf(hidden))));
        }
        String prefix = g.string("reward-card.item-lore.prefix", "");
        if (!prefix.isEmpty()) out.replaceAll(l -> prefix + l);
        return out;
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
