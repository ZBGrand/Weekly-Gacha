package com.weeklygacha.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.ArrayList;
import java.util.List;

public final class Text {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private Text() {
    }

    /** Parse MiniMessage, mặc định tắt chữ nghiêng (để dùng cho tên/lore item). */
    public static Component mm(String input, TagResolver... resolvers) {
        return MM.deserialize(input == null ? "" : input, resolvers)
                .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public static List<Component> mmList(List<String> lines, TagResolver... resolvers) {
        List<Component> out = new ArrayList<>(lines.size());
        for (String line : lines) {
            out.add(mm(line, resolvers));
        }
        return out;
    }

    public static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    /** Vô hiệu hoá thẻ MiniMessage trong chuỗi do người dùng/plugin khác cung cấp. */
    public static String escape(String s) {
        return MM.escapeTags(s == null ? "" : s);
    }
}
