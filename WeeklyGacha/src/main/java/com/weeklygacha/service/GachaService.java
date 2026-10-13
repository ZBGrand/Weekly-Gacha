package com.weeklygacha.service;

import com.weeklygacha.WeeklyGachaPlugin;
import com.weeklygacha.model.Banner;
import com.weeklygacha.model.HistoryEntry;
import com.weeklygacha.model.PityConfig;
import com.weeklygacha.model.PlayerBannerData;
import com.weeklygacha.model.PlayerData;
import com.weeklygacha.model.PullEntry;
import com.weeklygacha.model.PullResult;
import com.weeklygacha.model.Reward;
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
import java.util.function.ToDoubleFunction;

import static com.weeklygacha.service.MessageService.comp;
import static com.weeklygacha.service.MessageService.ph;

/** Logic quay: kiểm tra, trừ tiền, roll theo tỷ lệ + pity, trao thưởng, lưu lịch sử. */
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
     * Mọi thông báo lỗi được gửi thẳng cho người chơi; trả về {@link PullResult#FAIL} khi thất bại.
     * Khi thất bại ở bất kỳ bước nào trước khi trừ tiền, người chơi KHÔNG bị mất tiền hay pity.
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
        List<PullEntry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int pityAtPull = working.getPity() + 1;
            Reward reward = roll(banner, working);
            ItemStack item = null;
            if (reward.provider().givesItem()) {
                item = plugin.rewards().createItem(reward);
                if (item == null) {
                    msg.send(player, "reward-unavailable", ph("reward", reward.id()));
                    return PullResult.FAIL;
                }
            }
            entries.add(new PullEntry(reward, pityAtPull, item));
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

        // 4) Thông báo
        String costText = cost > 0 && eco != null ? eco.format(cost) : "0";
        msg.send(player, "pull-summary", ph("count", String.valueOf(count)), ph("cost", costText),
                comp("banner", Text.mm(banner.displayName())));
        broadcastRare(player, banner, entries);

        return new PullResult(true, entries, cost);
    }

    // ------------------------------------------------------------------ thuật toán roll

    /**
     * Chọn một vật phẩm và cập nhật {@code state}.
     * <ul>
     *   <li>Xác suất cơ bản của mỗi vật phẩm = chance / tổng chance.</li>
     *   <li>Soft pity: từ lượt softStart, trọng số các vật phẩm thuộc độ hiếm pity nhân với
     *       {@code 1 + softBoost * (lượt - softStart + 1)}.</li>
     *   <li>Hard pity: lượt thứ {@code hard} chắc chắn ra độ hiếm pity.</li>
     *   <li>Guarantee featured: lần ra độ hiếm pity gần nhất không phải featured thì lần kế tiếp chắc chắn featured.</li>
     * </ul>
     */
    Reward roll(Banner banner, PlayerBannerData state) {
        PityConfig pity = banner.pity();
        int pullNumber = state.getPity() + 1;
        Reward result;

        if (pity.enabled() && pullNumber >= pity.hard()) {
            result = pick(banner.pityPool(), Reward::chance);
        } else {
            double boost = 1.0;
            if (pity.enabled() && pullNumber >= pity.softStart()) {
                boost = 1.0 + pity.softBoost() * (pullNumber - pity.softStart() + 1);
            }
            final double factor = boost;
            result = pick(banner.rewards(),
                    r -> pity.enabled() && r.rarity() == pity.rarity() ? r.chance() * factor : r.chance());
        }

        if (pity.enabled() && result.rarity() == pity.rarity()) {
            if (banner.hasGuarantee() && state.isGuaranteed() && !result.featured()) {
                result = pick(banner.featuredPool(), Reward::chance);
            }
            state.setGuaranteed(banner.hasGuarantee() && !result.featured());
            state.setPity(0);
        } else {
            state.setPity(pullNumber);
        }
        state.setTotalPulls(state.getTotalPulls() + 1);
        return result;
    }

    private static Reward pick(List<Reward> pool, ToDoubleFunction<Reward> weight) {
        double total = 0;
        for (Reward r : pool) total += weight.applyAsDouble(r);
        double roll = ThreadLocalRandom.current().nextDouble() * total;
        double acc = 0;
        for (Reward r : pool) {
            acc += weight.applyAsDouble(r);
            if (roll < acc) return r;
        }
        return pool.get(pool.size() - 1);
    }

    // ------------------------------------------------------------------ thông báo

    private void broadcastRare(Player player, Banner banner, List<PullEntry> entries) {
        Set<String> rarities = new HashSet<>();
        for (String s : plugin.getConfig().getStringList("settings.broadcast-rarities")) {
            rarities.add(s.toUpperCase(Locale.ROOT));
        }
        for (PullEntry e : entries) {
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
}
