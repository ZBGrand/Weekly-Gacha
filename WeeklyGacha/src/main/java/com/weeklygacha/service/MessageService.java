package com.weeklygacha.service;

import com.weeklygacha.WeeklyGachaPlugin;
import com.weeklygacha.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;

import java.util.List;

/** Đọc tin nhắn từ config (mục messages) và gửi bằng MiniMessage. */
public final class MessageService {

    private final WeeklyGachaPlugin plugin;

    public MessageService(WeeklyGachaPlugin plugin) {
        this.plugin = plugin;
    }

    public String raw(String key) {
        return plugin.getConfig().getString("messages." + key, "<red>Thiếu tin nhắn: " + key);
    }

    public List<String> rawList(String key) {
        return plugin.getConfig().getStringList("messages." + key);
    }

    public Component component(String key, TagResolver... resolvers) {
        return Text.mm(raw("prefix") + raw(key), resolvers);
    }

    public void send(CommandSender to, String key, TagResolver... resolvers) {
        to.sendMessage(component(key, resolvers));
    }

    public void sendList(CommandSender to, String key, TagResolver... resolvers) {
        for (String line : rawList(key)) {
            to.sendMessage(Text.mm(line, resolvers));
        }
    }

    /** Placeholder văn bản thuần (không parse thẻ). */
    public static TagResolver ph(String name, String value) {
        return Placeholder.unparsed(name, value);
    }

    /** Placeholder là Component đã dựng sẵn. */
    public static TagResolver comp(String name, Component value) {
        return Placeholder.component(name, value);
    }
}
