package com.kingdomsofash.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;

/** MiniMessage helpers. */
public final class Text {
    public static final MiniMessage MM = MiniMessage.miniMessage();
    public static String prefix = "<dark_gray>[<dark_red>Ash<dark_gray>] ";

    private Text() {
    }

    public static Component mm(String s) {
        return MM.deserialize(s == null ? "" : s);
    }

    public static Component mm(String s, String placeholder, String value) {
        return MM.deserialize(s == null ? "" : s, Placeholder.unparsed(placeholder, value));
    }

    /** Non-italic variant for item names / lore. */
    public static Component item(String s) {
        return mm(s).decoration(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public static void send(CommandSender to, String miniMessage) {
        to.sendMessage(mm(prefix + miniMessage));
    }

    public static void raw(CommandSender to, String miniMessage) {
        to.sendMessage(mm(miniMessage));
    }

    /** Escapes user supplied text (player names etc.) for safe embedding in MiniMessage strings. */
    public static String esc(String s) {
        return MM.escapeTags(s == null ? "" : s);
    }
}
