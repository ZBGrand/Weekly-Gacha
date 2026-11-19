package com.weeklygacha.command;

import com.weeklygacha.WeeklyGachaPlugin;
import com.weeklygacha.service.LunacyService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.weeklygacha.service.MessageService.ph;

/** /lunacy - xem số dư, mở cửa hàng, mua Lunacy bằng PlayerPoints, bảng xếp hạng. */
public final class LunacyCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUB = List.of("balance", "shop", "buy", "top", "help");

    private final WeeklyGachaPlugin plugin;

    public LunacyCommand(WeeklyGachaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        LunacyService lu = plugin.lunacy();
        String sub = args.length == 0 ? "balance" : args[0].toLowerCase(Locale.ROOT);

        if (sub.equals("top")) {
            boolean any = false;
            plugin.messages().send(sender, "lunacy-top-header");
            for (int i = 1; i <= 10; i++) {
                String[] row = lu.top(i);
                if (row == null) break;
                any = true;
                plugin.messages().sendRaw(sender, "lunacy-top-line", ph("rank", String.valueOf(i)),
                        ph("player", row[0]), ph("balance", lu.plain(Long.parseLong(row[1]))));
            }
            if (!any) plugin.messages().sendRaw(sender, "lunacy-top-empty");
            return true;
        }
        if (sub.equals("help")) {
            plugin.messages().sendList(sender, "lunacy-help");
            return true;
        }
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "players-only");
            return true;
        }
        if (!player.hasPermission("weeklygacha.use")) {
            plugin.messages().send(player, "no-permission");
            return true;
        }

        switch (sub) {
            case "balance", "bal" -> {
                long bal = lu.balance(player.getUniqueId());
                if (bal < 0) {
                    plugin.messages().send(player, "data-loading");
                    return true;
                }
                plugin.messages().send(player, "lunacy-balance", ph("balance", lu.plain(bal)));
            }
            case "shop" -> plugin.menu().openShop(player, null);
            case "buy" -> {
                if (args.length < 2) {
                    plugin.menu().openShop(player, null);
                    return true;
                }
                LunacyService.ShopPackage pk = lu.pack(args[1]);
                if (pk != null) {
                    plugin.menu().buy(player, pk.total(), pk.price(), "BUY:" + pk.id());
                    return true;
                }
                long amount;
                try {
                    amount = Long.parseLong(args[1]);
                } catch (NumberFormatException e) {
                    plugin.messages().send(player, "lunacy-package-unknown", ph("id", args[1]));
                    return true;
                }
                if (!lu.customEnabled()) {
                    plugin.messages().send(player, "lunacy-custom-disabled");
                    return true;
                }
                if (amount < lu.customMin() || amount > lu.customMax()) {
                    plugin.messages().send(player, "lunacy-amount-invalid",
                            ph("min", lu.number(lu.customMin())), ph("max", lu.number(lu.customMax())));
                    return true;
                }
                plugin.menu().buy(player, amount, lu.customPrice(amount), "BUY:custom");
            }
            default -> plugin.messages().sendList(player, "lunacy-help");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        if (args.length == 1) {
            for (String s : SUB) if (s.startsWith(prefix)) out.add(s);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("buy")) {
            for (LunacyService.ShopPackage pk : plugin.lunacy().packages()) {
                if (pk.id().toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(pk.id());
            }
        }
        return out;
    }
}
