package com.weeklygacha.command;

import com.weeklygacha.WeeklyGachaPlugin;
import com.weeklygacha.model.Banner;
import com.weeklygacha.model.BannerType;
import com.weeklygacha.model.PlayerBannerData;
import com.weeklygacha.model.PlayerData;
import com.weeklygacha.model.PullResult;
import com.weeklygacha.service.BannerManager;
import com.weeklygacha.util.Text;
import com.weeklygacha.util.TimeUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.weeklygacha.service.MessageService.comp;
import static com.weeklygacha.service.MessageService.ph;

/** /gacha - lệnh dành cho người chơi. */
public final class GachaCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUB = List.of("open", "pull", "rates", "history", "pity", "info", "help");

    private final WeeklyGachaPlugin plugin;

    public GachaCommand(WeeklyGachaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "players-only");
            return true;
        }
        if (!player.hasPermission("weeklygacha.use")) {
            plugin.messages().send(player, "no-permission");
            return true;
        }

        String sub = args.length == 0 ? "open" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "open" -> {
                Banner b = null;
                if (args.length > 1) {
                    b = resolve(player, args[1]);
                    if (b == null) return true;
                }
                plugin.menu().openMain(player, b);
            }
            case "pull" -> {
                int count = 1;
                Banner b = null;
                for (int i = 1; i < args.length; i++) {
                    if (args[i].equals("1")) count = 1;
                    else if (args[i].equals("10")) count = 10;
                    else {
                        b = resolve(player, args[i]);
                        if (b == null) return true;
                    }
                }
                if (b == null) b = plugin.menu().selectedBanner(player);
                if (b == null) {
                    plugin.messages().send(player, "no-banner");
                    return true;
                }
                PullResult result = plugin.gacha().pull(player, b, count);
                if (result.success()) plugin.menu().openResult(player, b, result.entries(), count);
            }
            case "rates" -> {
                Banner b = args.length > 1 ? resolve(player, args[1]) : plugin.menu().selectedBanner(player);
                if (b == null) {
                    if (args.length <= 1) plugin.messages().send(player, "no-banner");
                    return true;
                }
                plugin.menu().openRates(player, b, 0);
            }
            case "history" -> plugin.menu().openHistory(player, 0);
            case "pity" -> {
                Banner b = args.length > 1 ? resolve(player, args[1]) : plugin.menu().selectedBanner(player);
                if (b == null) {
                    if (args.length <= 1) plugin.messages().send(player, "no-banner");
                    return true;
                }
                PlayerData data = plugin.players().get(player.getUniqueId());
                if (data == null) {
                    plugin.messages().send(player, "data-loading");
                    return true;
                }
                PlayerBannerData pd = data.get(b.id());
                player.sendMessage(Text.mm(plugin.messages().raw("prefix")).append(Text.mm(b.displayName())));
                for (String line : plugin.menu().pityLines(b, pd)) {
                    player.sendMessage(Text.mm(line));
                }
            }
            case "info" -> {
                BannerManager bm = plugin.banners();
                for (Banner b : bm.activeBanners()) {
                    boolean limited = b.type() == BannerType.LIMITED;
                    player.sendMessage(Text.mm(plugin.messages().raw("info-line"),
                            comp("banner", Text.mm(b.displayName())),
                            ph("type", limited ? "giới hạn" : "thường"),
                            ph("remaining", limited ? TimeUtil.duration(bm.remainingMillis()) : "∞")));
                }
            }
            default -> plugin.messages().sendList(player, "help");
        }
        return true;
    }

    /** Tìm banner đang mở theo id; tự báo lỗi cho người chơi nếu không hợp lệ. */
    private Banner resolve(Player player, String id) {
        Banner b = plugin.banners().get(id);
        if (b == null) {
            plugin.messages().send(player, "banner-not-found", ph("id", id));
            return null;
        }
        if (!plugin.banners().isActive(b)) {
            plugin.messages().send(player, "banner-inactive");
            return null;
        }
        return b;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : SUB) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
            }
        } else if (args.length >= 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
            if (sub.equals("pull") && args.length == 2) {
                for (String s : List.of("1", "10")) if (s.startsWith(prefix)) out.add(s);
            }
            if (List.of("open", "pull", "rates", "pity").contains(sub)) {
                for (Banner b : plugin.banners().activeBanners()) {
                    if (b.id().startsWith(prefix)) out.add(b.id());
                }
            }
        }
        return out;
    }
}
