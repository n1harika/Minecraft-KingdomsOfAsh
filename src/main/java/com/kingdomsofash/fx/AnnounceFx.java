package com.kingdomsofash.fx;

import com.kingdomsofash.config.Settings;
import com.kingdomsofash.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.List;

/** Server-wide presentation: the claim announcement, ownership transfers and holder deaths. */
public final class AnnounceFx {
    private final Fx fx;
    private final Settings s;

    public AnnounceFx(Fx fx, Settings s) {
        this.fx = fx;
        this.s = s;
    }

    /** Title + layered sounds + timed chat lines. Safe to call exactly once per claim (caller guards). */
    public void claim(Player claimant) {
        if (!s.raw().getBoolean("announcement.enabled", true)) return;
        String name = Text.esc(claimant.getName());
        Title title = Title.title(
                Text.mm(s.raw().getString("announcement.title", "")),
                Text.mm(s.raw().getString("announcement.subtitle", "")),
                Title.Times.times(
                        Duration.ofMillis(s.raw().getLong("announcement.title-fade-in-ms", 400)),
                        Duration.ofMillis(s.raw().getLong("announcement.title-stay-ms", 4500)),
                        Duration.ofMillis(s.raw().getLong("announcement.title-fade-out-ms", 1200))));

        // t=0: the bell tolls and the world shudders
        fx.soundGlobal("block.bell.use", 1.0f, 0.5f);
        fx.soundGlobal("entity.wither.spawn", 0.55f, 0.6f);
        Bukkit.getServer().showTitle(title);
        fx.later(10, () -> fx.soundGlobal("entity.lightning_bolt.thunder", 0.9f, 0.7f));
        fx.later(30, () -> fx.soundGlobal("block.beacon.activate", 0.8f, 0.5f));
        fx.later(60, () -> fx.soundGlobal("entity.ender_dragon.growl", 0.45f, 0.6f));

        List<String> lines = s.raw().getStringList("announcement.chat");
        long delay = Math.max(1, s.raw().getLong("announcement.line-delay-ticks", 24));
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).replace("<player>", name);
            long t = 6 + i * delay;
            fx.later(t, () -> {
                Component c = Text.mm(line);
                Bukkit.getServer().sendMessage(c);
                fx.soundGlobal("block.note_block.bass", 0.5f, 0.6f);
            });
        }
        // the claimant's spot erupts
        Location l = claimant.getLocation().add(0, 1, 0);
        List<Player> v = fx.viewers(l, 64);
        fx.spawn(v, Particle.DUST_PLUME, l, 24, 0.6, 0.6, 0.6, 0.02, null, true);
        fx.dust(v, l, Fx.CRIMSON, 1.6f, 60, 1.2, true);
        fx.spawn(v, Particle.TOTEM_OF_UNDYING, l, 30, 0.7, 1.0, 0.7, 0.3, null, true);
    }

    public void transfer(Player newHolder) {
        String msg = s.raw().getString("announcement.transfer", "");
        Bukkit.getServer().sendMessage(Text.mm(msg.replace("<player>", Text.esc(newHolder.getName()))));
        fx.soundGlobal("block.bell.use", 0.6f, 0.8f);
        fx.soundGlobal("item.trident.return", 0.5f, 0.6f);
        Location l = newHolder.getLocation().add(0, 1, 0);
        List<Player> v = fx.viewers(l);
        fx.dust(v, l, Fx.CRIMSON, 1.4f, 30, 0.7, true);
        fx.spawn(v, Particle.SOUL, l, 10, 0.5, true);
    }

    public void fallen(String holderName, Location where, boolean reveal) {
        String msg = s.raw().getString("announcement.fallen", "").replace("<player>", Text.esc(holderName));
        Component c = Text.mm(msg);
        if (reveal) {
            c = c.append(Text.mm(String.format(" <dark_gray>(<gray>%d %d %d<dark_gray>)", where.getBlockX(), where.getBlockY(), where.getBlockZ())));
        }
        Bukkit.getServer().sendMessage(c);
        fx.soundGlobal("entity.wither.death", 0.35f, 0.7f);
        fx.soundGlobal("block.bell.resonate", 0.5f, 0.6f);
    }
}
