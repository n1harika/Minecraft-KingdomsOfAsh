package com.kingdomsofash.persist;

import com.kingdomsofash.weapon.WeaponLedger;

import java.util.HashMap;
import java.util.Map;

/** Root of data/state.json. */
public final class PersistentState {
    public int schema = 1;
    public WeaponLedger weapon = new WeaponLedger();
    public ForgeState forge = new ForgeState();
    public RitualData ritual = new RitualData();
    /** player uuid -> wall-clock millis until which King's Exhaustion applies. */
    public Map<String, Long> exhaustionUntilMs = new HashMap<>();

    /** Repairs null collections after deserialisation of older or hand-edited files. */
    public PersistentState normalise() {
        if (weapon == null) weapon = new WeaponLedger();
        weapon.normalise();
        if (forge == null) forge = new ForgeState();
        if (ritual == null) ritual = new RitualData();
        if (exhaustionUntilMs == null) exhaustionUntilMs = new HashMap<>();
        return this;
    }
}
