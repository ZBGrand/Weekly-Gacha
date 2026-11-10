package com.weeklygacha.hook;

import com.weeklygacha.WeeklyGachaPlugin;
import com.weeklygacha.model.Banner;
import com.weeklygacha.model.PlayerBannerData;
import com.weeklygacha.model.PlayerData;
import com.weeklygacha.service.BannerManager;
import com.weeklygacha.service.LunacyService;
import com.weeklygacha.util.Text;
import com.weeklygacha.util.TimeUtil;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.Locale;

/**
 * PlaceholderAPI: %weeklygacha_<khoá>%. Danh sách đầy đủ trong README.
 * Chỉ được nạp khi PlaceholderAPI đang bật, nên plugin vẫn chạy bình thường khi không cài PlaceholderAPI.
 */
public final class GachaPlaceholders extends PlaceholderExpansion {

    private final WeeklyGachaPlugin plugin;

    public GachaPlaceholders(WeeklyGachaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "weeklygacha";
    }

    @Override
    public String getAuthor() {
        return "WeeklyGacha";
    }

    @Override
    @SuppressWarnings("deprecation")
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true; // không bị gỡ khi /papi reload
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        String key = params.toLowerCase(Locale.ROOT);
        LunacyService lu = plugin.lunacy();
        BannerManager bm = plugin.banners();

        // ---------------- không cần người chơi
        switch (key) {
            case "currency" -> {
                return legacy(lu.currencyName());
            }
            case "currency_plain" -> {
                return Text.plain(Text.mm(lu.currencyName()));
            }
            case "symbol" -> {
                return lu.symbol();
            }
            case "banner" -> {
                Banner b = bm.currentLimited();
                return b == null ? "-" : legacy(b.displayName());
            }
            case "banner_plain" -> {
                Banner b = bm.currentLimited();
                return b == null ? "-" : Text.plain(Text.mm(b.displayName()));
            }
            case "banner_id" -> {
                Banner b = bm.currentLimited();
                return b == null ? "" : b.id();
            }
            case "cycle" -> {
                return String.valueOf(bm.cycleNumber());
            }
            case "cycle_remaining" -> {
                return TimeUtil.duration(bm.remainingMillis());
            }
            case "cycle_end" -> {
                return TimeUtil.date(bm.endMillis(), bm.zone());
            }
            default -> {
            }
        }
        if (key.startsWith("top_")) return top(key.substring(4));

        // ---------------- theo người chơi
        if (player == null) return "";
        PlayerData data = plugin.players().get(player.getUniqueId());

        switch (key) {
            case "lunacy" -> {
                return String.valueOf(lunacy(data));
            }
            case "lunacy_formatted" -> {
                return lu.number(lunacy(data));
            }
            case "lunacy_short" -> {
                return lu.shortNumber(lunacy(data));
            }
            case "lunacy_display" -> {
                return lu.plain(lunacy(data));
            }
            case "points" -> {
                Player online = player.getPlayer();
                int p = online == null ? -1 : lu.points(online);
                return p < 0 ? "0" : String.valueOf(p);
            }
            case "total_pulls" -> {
                if (data == null) return "0";
                int sum = 0;
                for (PlayerBannerData d : data.all().values()) sum += d.getTotalPulls();
                return String.valueOf(sum);
            }
            default -> {
            }
        }

        // spark / pity / pulls / spark_threshold / spark_progress [_<banner>]
        for (String stat : new String[]{"spark_threshold", "spark_progress", "spark", "pity", "pulls"}) {
            if (!key.equals(stat) && !key.startsWith(stat + "_")) continue;
            String bannerId = key.length() > stat.length() ? key.substring(stat.length() + 1) : null;
            Banner b = bannerId == null ? bm.currentLimited() : bm.get(bannerId);
            if (b == null) return "0";
            PlayerBannerData d = data == null ? null : data.all().get(b.id());
            int threshold = Math.max(1, b.spark().threshold());
            return switch (stat) {
                case "spark_threshold" -> String.valueOf(threshold);
                case "spark_progress" -> String.valueOf(d == null ? 0 : Math.min(100, d.getSpark() * 100 / threshold));
                case "spark" -> String.valueOf(d == null ? 0 : d.getSpark());
                case "pity" -> String.valueOf(d == null ? 0 : d.getPity());
                default -> String.valueOf(d == null ? 0 : d.getTotalPulls());
            };
        }
        return null; // khoá lạ -> PlaceholderAPI giữ nguyên chữ
    }

    private static long lunacy(PlayerData data) {
        return data == null ? 0 : Math.max(0, data.lunacy().get());
    }

    /** top_<hạng>_name | top_<hạng>_amount | top_<hạng>_amount_raw */
    private String top(String rest) {
        int us = rest.indexOf('_');
        if (us <= 0) return null;
        int rank;
        try {
            rank = Integer.parseInt(rest.substring(0, us));
        } catch (NumberFormatException e) {
            return null;
        }
        String what = rest.substring(us + 1);
        String[] row = plugin.lunacy().top(rank);
        String empty = plugin.getConfig().getString("lunacy.leaderboard-empty", "-");
        return switch (what) {
            case "name" -> row == null ? empty : row[0];
            case "amount" -> row == null ? empty : plugin.lunacy().number(Long.parseLong(row[1]));
            case "amount_raw" -> row == null ? "0" : row[1];
            case "amount_short" -> row == null ? empty : plugin.lunacy().shortNumber(Long.parseLong(row[1]));
            default -> null;
        };
    }

    /** MiniMessage -> mã màu § (hầu hết plugin tab/scoreboard đọc được, kể cả màu hex). */
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('§').hexColors().useUnusualXRepeatedCharacterHexFormat().build();

    private static String legacy(String miniMessage) {
        return LEGACY.serialize(Text.mm(miniMessage));
    }
}
