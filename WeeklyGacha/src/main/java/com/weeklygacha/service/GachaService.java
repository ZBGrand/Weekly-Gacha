package com.weeklygacha.service;

import com.weeklygacha.WeeklyGachaPlugin;
import com.weeklygacha.model.Banner;
import com.weeklygacha.model.HistoryEntry;
import com.weeklygacha.model.PlayerBannerData;
import com.weeklygacha.model.PlayerData;
import com.weeklygacha.model.PullEntry;
import com.weeklygacha.model.PullResult;
import com.weeklygacha.model.Rarity;
import com.weeklygacha.model.Reward;
import com.weeklygacha.model.SparkConfig;
import com.weeklygacha.util.Text;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static com.weeklygacha.service.MessageService.comp;
import static com.weeklygacha.service.MessageService.ph;

/**
 * Logic quay kiểu Limbus Company: tỷ lệ riêng từng vật phẩm, mười lượt có bảo đảm độ hiếm tối thiểu,
 * và "spark" (tích đủ lượt thì đổi thẳng vật phẩm nổi bật).
 */
public final class GachaService {

    private final WeeklyGachaPlugin plugin;
    private final Map<UUID, Long> lastPull = new HashMap<>();

    public GachaService(WeeklyGachaPlugin plugin) {
        this.plugin = plugin;
    }

    public void forget(UUID uuid) {
        lastPull.remove(uuid);
    }

    /**
     * Quay {@code count} lượt (1 hoặc 10) trên banner. Gọi trên luồng chính.
     * Lỗi được gửi thẳng cho người chơi và trả về {@link PullResult#FAIL}; mọi lỗi xảy ra trước khi
     * trừ tiền đều không làm mất tiền hay thay đổi dữ liệu.
     * Chưa gửi thông báo/broadcast: hãy gọi {@link #announce} sau khi hiển thị kết quả (hoạt ảnh).
     */
    public PullResult pull(Player player, Banner banner, int count) {
        MessageService msg = plugin.messages();

        if (!player.hasPermission("weeklygacha.use")) {
            msg.send(player, "no-permission");
            return PullResult.FAIL;
        }
        if (!plugin.banners().isActive(banner)) {
            msg.send(player, "banner-inactive");
            return PullResult.FAIL;
        }
        PlayerData data = plugin.players().get(player.getUniqueId());
        if (data == null) {
            msg.send(player, "data-loading");
            return PullResult.FAIL;
        }

        long now = System.currentTimeMillis();
        long cooldown = plugin.getConfig().getLong("settings.pull-cooldown-ms", 500);
        Long last = lastPull.get(player.getUniqueId());
        if (last != null && now - last < cooldown) {
            msg.send(player, "cooldown");
            return PullResult.FAIL;
        }
        lastPull.put(player.getUniqueId(), now);

        double cost = count == 10 ? banner.tenCost() : banner.singleCost() * count;
        Economy eco = plugin.economy();
        if (cost > 0) {
            if (eco == null) {
                msg.send(player, "economy-missing");
                return PullResult.FAIL;
            }
            if (!eco.has(player, cost)) {
                msg.send(player, "not-enough-money",
                        ph("cost", eco.format(cost)), ph("balance", eco.format(eco.getBalance(player))));
                return PullResult.FAIL;
            }
        }

        // 1) Roll trên bản sao -> chưa đụng vào dữ liệu thật
        PlayerBannerData working = data.get(banner.id()).copy();
        Rarity guarantee = count == 10 ? banner.spark().tenGuarantee() : null;
        boolean guaranteeMet = false;
        List<PullEntry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int sinceTop = working.getPity() + 1;
            boolean forced = guarantee != null && !guaranteeMet && i == count - 1;
            Reward reward = roll(banner, working, forced ? guarantee : null);
            if (guarantee != null && reward.rarity().ordinal() >= guarantee.ordinal()) guaranteeMet = true;

            ItemStack item = null;
            if (reward.provider().givesItem()) {
                item = plugin.rewards().createItem(reward);
                if (item == null) {
                    msg.send(player, "reward-unavailable", ph("reward", reward.id()));
                    return PullResult.FAIL;
                }
            }
            entries.add(new PullEntry(reward, sinceTop, item));
        }

        // 2) Trừ tiền
        if (cost > 0) {
            EconomyResponse resp = eco.withdrawPlayer(player, cost);
            if (!resp.transactionSuccess()) {
                msg.send(player, "withdraw-failed", ph("reason", String.valueOf(resp.errorMessage)));
                return PullResult.FAIL;
            }
        }

        // 3) Chốt dữ liệu, trao thưởng, lưu DB
        data.put(banner.id(), working);
        int cycle = plugin.banners().cycleNumber();
        List<HistoryEntry> history = new ArrayList<>(count);
        for (PullEntry e : entries) {
            plugin.rewards().give(player, e.reward(), e.item() == null ? null : e.item().clone());
            history.add(new HistoryEntry(0, banner.id(), cycle, e.reward().id(),
                    plugin.rewards().nameOf(e.reward()), e.reward().rarity(), e.pityAtPull(), now));
        }
        plugin.database().recordPulls(player.getUniqueId(), player.getName(), banner.id(), working.copy(), history);

        return new PullResult(true, entries, cost);
    }

    /**
     * Đổi spark lấy vật phẩm {@code chosen} (phải nằm trong {@link Banner#sparkPool()}). Gọi trên luồng chính.
     *
     * @return true nếu đổi thành công
     */
    public boolean exchange(Player player, Banner banner, Reward chosen) {
        MessageService msg = plugin.messages();
        SparkConfig spark = banner.spark();

        if (!player.hasPermission("weeklygacha.use")) {
            msg.send(player, "no-permission");
            return false;
        }
        if (!plugin.banners().isActive(banner)) {
            msg.send(player, "banner-inactive");
            return false;
        }
        PlayerData data = plugin.players().get(player.getUniqueId());
        if (data == null) {
            msg.send(player, "data-loading");
            return false;
        }
        if (!spark.enabled() || !banner.sparkPool().contains(chosen)) {
            msg.send(player, "spark-disabled");
            return false;
        }
        PlayerBannerData current = data.get(banner.id());
        if (current.getSpark() < spark.threshold()) {
            msg.send(player, "spark-not-ready",
                    ph("current", String.valueOf(current.getSpark())), ph("threshold", String.valueOf(spark.threshold())));
            return false;
        }

        ItemStack item = null;
        if (chosen.provider().givesItem()) {
            item = plugin.rewards().createItem(chosen);
            if (item == null) {
                msg.send(player, "reward-unavailable", ph("reward", chosen.id()));
                return false;
            }
        }

        PlayerBannerData working = current.copy();
        working.setSpark(working.getSpark() - spark.threshold());
        data.put(banner.id(), working);

        plugin.rewards().give(player, chosen, item);
        String name = plugin.rewards().nameOf(chosen);
        // pityAtPull = 0 đánh dấu lượt này là đổi bằng spark
        HistoryEntry entry = new HistoryEntry(0, banner.id(), plugin.banners().cycleNumber(), chosen.id(), name,
                chosen.rarity(), 0, System.currentTimeMillis());
        plugin.database().recordPulls(player.getUniqueId(), player.getName(), banner.id(), working.copy(), List.of(entry));

        msg.send(player, "spark-exchanged",
                comp("item", Text.mm(chosen.rarity().color() + Text.escape(name))),
                comp("banner", Text.mm(banner.displayName())));
        return true;
    }

    /** Gửi tóm tắt cho người quay và thông báo toàn server cho vật phẩm hiếm. Gọi sau khi hiển thị kết quả. */
    public void announce(Player player, Banner banner, PullResult result) {
        if (!result.success()) return;
        Economy eco = plugin.economy();
        String costText = result.cost() > 0 && eco != null ? eco.format(result.cost()) : "0";
        if (player.isOnline()) {
            plugin.messages().send(player, "pull-summary", ph("count", String.valueOf(result.entries().size())),
                    ph("cost", costText), comp("banner", Text.mm(banner.displayName())));
        }

        Set<String> rarities = new HashSet<>();
        for (String s : plugin.getConfig().getStringList("settings.broadcast-rarities")) {
            rarities.add(s.toUpperCase(Locale.ROOT));
        }
        for (PullEntry e : result.entries()) {
            Reward r = e.reward();
            boolean announce = r.broadcast() != null ? r.broadcast() : rarities.contains(r.rarity().name());
            if (!announce) continue;
            String name = plugin.rewards().nameOf(r);
            Bukkit.broadcast(plugin.messages().component("broadcast-rare",
                    ph("player", player.getName()),
                    comp("item", Text.mm(r.rarity().color() + Text.escape(name))),
                    comp("banner", Text.mm(banner.displayName())),
                    ph("pity", String.valueOf(e.pityAtPull()))));
        }
    }

    // ------------------------------------------------------------------ thuật toán roll

    /**
     * Chọn một vật phẩm theo tỷ lệ riêng và cập nhật trạng thái.
     * Tỷ lệ của mỗi vật phẩm = chance / tổng chance. Nếu {@code minRarity} khác null thì chỉ chọn
     * trong các vật phẩm có độ hiếm từ mức đó trở lên (dùng cho bảo đảm của lượt thứ 10).
     */
    Reward roll(Banner banner, PlayerBannerData state, Rarity minRarity) {
        List<Reward> pool = banner.rewards();
        if (minRarity != null) {
            List<Reward> filtered = pool.stream().filter(r -> r.rarity().ordinal() >= minRarity.ordinal()).toList();
            if (!filtered.isEmpty()) pool = filtered;
        }
        Reward result = pick(pool);

        state.setTotalPulls(state.getTotalPulls() + 1);
        state.setSpark(state.getSpark() + 1);
        state.setPity(result.rarity() == banner.spark().rarity() ? 0 : state.getPity() + 1);
        return result;
    }

    private static Reward pick(List<Reward> pool) {
        double total = 0;
        for (Reward r : pool) total += r.chance();
        double roll = ThreadLocalRandom.current().nextDouble() * total;
        double acc = 0;
        for (Reward r : pool) {
            acc += r.chance();
            if (roll < acc) return r;
        }
        return pool.get(pool.size() - 1);
    }
}
