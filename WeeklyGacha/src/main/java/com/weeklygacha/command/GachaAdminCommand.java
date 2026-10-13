package com.weeklygacha.command;

import com.weeklygacha.WeeklyGachaPlugin;
import com.weeklygacha.model.Banner;
import com.weeklygacha.model.BannerType;
import com.weeklygacha.model.PlayerData;
import com.weeklygacha.model.Reward;
import com.weeklygacha.service.BannerManager;
import com.weeklygacha.util.Text;
import com.weeklygacha.util.TimeUtil;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static com.weeklygacha.service.MessageService.comp;
import static com.weeklygacha.service.MessageService.ph;

/** /gachaadmin - quản trị: đổi banner thủ công, đặt lại chu kỳ, reset pity, xem lịch sử... */
public final class GachaAdminCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUB = List.of(
            "info", "list", "setbanner", "auto", "next", "restartcycle", "reload", "resetpity", "history", "check");

    private final WeeklyGachaPlugin plugin;

    public GachaAdminCommand(WeeklyGachaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("weeklygacha.admin")) {
            plugin.messages().send(sender, "no-permission");
            return true;
        }
        if (args.length == 0) {
            plugin.messages().sendList(sender, "admin-usage");
            return true;
        }
        BannerManager bm = plugin.banners();

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "info" -> {
                Banner active = bm.currentLimited();
                plugin.messages().sendList(sender, "admin-info",
                        ph("cycle", String.valueOf(bm.cycleNumber())),
                        ph("length", String.valueOf(bm.lengthDays())),
                        ph("start", TimeUtil.date(bm.startMillis(), bm.zone())),
                        ph("end", TimeUtil.date(bm.endMillis(), bm.zone())),
                        ph("remaining", TimeUtil.duration(bm.remainingMillis())),
                        ph("active", active == null ? "-" : active.id()),
                        ph("override", bm.hasOverride() ? "có" : "không"));
            }
            case "list" -> {
                for (Banner b : bm.all()) {
                    String mark = bm.isActive(b) ? "<green>● " : "<gray>○ ";
                    sender.sendMessage(Text.mm(mark + "<white>" + b.id() + " <dark_gray>(" + b.type() + ", "
                            + b.rewards().size() + " vật phẩm)"));
                }
            }
            case "setbanner" -> {
                if (args.length < 2) {
                    plugin.messages().sendList(sender, "admin-usage");
                    return true;
                }
                Banner b = bm.get(args[1]);
                if (b == null || b.type() != BannerType.LIMITED) {
                    plugin.messages().send(sender, "admin-banner-invalid", ph("id", args[1]));
                    return true;
                }
                bm.setOverride(b);
                plugin.menu().closeAll();
                plugin.messages().send(sender, "admin-banner-set", comp("banner", Text.mm(b.displayName())));
            }
            case "auto" -> {
                bm.clearOverride();
                plugin.menu().closeAll();
                Banner b = bm.currentLimited();
                plugin.messages().send(sender, "admin-auto",
                        comp("banner", Text.mm(b == null ? "-" : b.displayName())));
            }
            case "next" -> {
                Banner b = bm.next();
                if (b == null) {
                    plugin.messages().send(sender, "no-banner");
                    return true;
                }
                plugin.menu().closeAll();
                plugin.messages().send(sender, "admin-next", comp("banner", Text.mm(b.displayName())));
            }
            case "restartcycle" -> {
                Long millis;
                if (args.length < 2) {
                    millis = System.currentTimeMillis();
                } else if (args[1].equalsIgnoreCase("config")) {
                    millis = null;
                } else {
                    long parsed = bm.parseDate(args[1]);
                    if (parsed < 0) {
                        plugin.messages().send(sender, "admin-restart-invalid");
                        return true;
                    }
                    millis = parsed;
                }
                bm.setCycleStart(millis);
                plugin.menu().closeAll();
                plugin.messages().send(sender, "admin-restart", ph("start", TimeUtil.date(bm.startMillis(), bm.zone())));
            }
            case "reload" -> {
                plugin.reloadAll();
                plugin.messages().send(sender, "admin-reload", ph("banners", String.valueOf(bm.all().size())));
            }
            case "resetpity" -> {
                if (args.length < 2) {
                    plugin.messages().sendList(sender, "admin-usage");
                    return true;
                }
                OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(args[1]);
                if (target == null) {
                    plugin.messages().send(sender, "admin-player-unknown", ph("player", args[1]));
                    return true;
                }
                String bannerId = null;
                if (args.length > 2) {
                    Banner b = bm.get(args[2]);
                    if (b == null) {
                        plugin.messages().send(sender, "banner-not-found", ph("id", args[2]));
                        return true;
                    }
                    bannerId = b.id();
                }
                UUID uuid = target.getUniqueId();
                plugin.database().resetPity(uuid, bannerId);
                PlayerData cached = plugin.players().get(uuid);
                if (cached != null) {
                    for (var entry : cached.all().entrySet()) {
                        if (bannerId == null || entry.getKey().equals(bannerId)) {
                            entry.getValue().setPity(0);
                            entry.getValue().setGuaranteed(false);
                        }
                    }
                }
                plugin.messages().send(sender, "admin-reset", ph("player", args[1]));
            }
            case "history" -> {
                if (args.length < 2) {
                    plugin.messages().sendList(sender, "admin-usage");
                    return true;
                }
                OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(args[1]);
                if (target == null) {
                    plugin.messages().send(sender, "admin-player-unknown", ph("player", args[1]));
                    return true;
                }
                int limit = 10;
                if (args.length > 2) {
                    try {
                        limit = Math.max(1, Math.min(50, Integer.parseInt(args[2])));
                    } catch (NumberFormatException ignored) {
                        // giữ mặc định
                    }
                }
                plugin.database().history(target.getUniqueId(), limit, 0).whenComplete((list, error) ->
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (error != null || list == null) {
                                plugin.messages().send(sender, "history-error");
                                return;
                            }
                            for (var e : list) {
                                Banner b = bm.get(e.bannerId());
                                sender.sendMessage(Text.mm(plugin.messages().raw("admin-history-line"),
                                        ph("time", TimeUtil.date(e.timestamp(), bm.zone())),
                                        comp("banner", Text.mm(b != null ? b.displayName() : Text.escape(e.bannerId()))),
                                        comp("item", Text.mm(e.rarity().color() + Text.escape(e.rewardName()))),
                                        ph("pity", String.valueOf(e.pityAtPull()))));
                            }
                        }));
            }
            case "check" -> {
                int total = 0;
                int bad = 0;
                for (Banner b : bm.all()) {
                    for (Reward r : b.rewards()) {
                        total++;
                        String problem = plugin.rewards().check(r);
                        if (problem != null) {
                            bad++;
                            plugin.messages().send(sender, "admin-check-fail",
                                    ph("banner", b.id()), ph("reward", r.id()), ph("provider", r.provider().name()));
                        }
                    }
                }
                if (bad == 0) plugin.messages().send(sender, "admin-check-ok", ph("count", String.valueOf(total)));
            }
            default -> plugin.messages().sendList(sender, "admin-usage");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (!sender.hasPermission("weeklygacha.admin")) return out;
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        if (args.length == 1) {
            for (String s : SUB) if (s.startsWith(prefix)) out.add(s);
        } else if (args.length == 2) {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "setbanner" -> {
                    for (Banner b : plugin.banners().all()) {
                        if (b.type() == BannerType.LIMITED && b.id().startsWith(prefix)) out.add(b.id());
                    }
                }
                case "resetpity", "history" -> {
                    for (var p : Bukkit.getOnlinePlayers()) {
                        if (p.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(p.getName());
                    }
                }
                case "restartcycle" -> {
                    if ("config".startsWith(prefix)) out.add("config");
                }
                default -> {
                }
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("resetpity")) {
            for (Banner b : plugin.banners().all()) {
                if (b.id().startsWith(prefix)) out.add(b.id());
            }
        }
        return out;
    }
}
