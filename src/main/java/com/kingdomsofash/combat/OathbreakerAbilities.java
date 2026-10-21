package com.kingdomsofash.combat;

import com.kingdomsofash.util.Text;
import com.kingdomsofash.weapon.WeaponService;
import org.bukkit.entity.Player;

/** Oathbreaker's binding into the central {@link AbilityDispatcher}. */
public final class OathbreakerAbilities implements AbilityDispatcher.WeaponAbilities {
    private final WeaponService weapons;
    private final RuptureService rupture;
    private final FuryManager fury;

    public OathbreakerAbilities(WeaponService weapons, RuptureService rupture, FuryManager fury) {
        this.weapons = weapons;
        this.rupture = rupture;
        this.fury = fury;
    }

    @Override
    public boolean handles(Player p) {
        return weapons.isWielding(p);
    }

    @Override
    public void ability1(Player p) {
        RuptureService.Result r = rupture.cast(p);
        if (r == RuptureService.Result.NOT_WIELDING) Text.send(p, "<gray>You must hold <dark_red>Oathbreaker<gray>.");
    }

    @Override
    public void ultimate(Player p) {
        FuryManager.Result r = fury.activate(p);
        if (r == FuryManager.Result.NOT_WIELDING) Text.send(p, "<gray>You must hold <dark_red>Oathbreaker<gray>.");
    }
}
