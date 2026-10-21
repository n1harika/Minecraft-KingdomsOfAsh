# Status: completed features, limitations, remaining issues

## Implemented
Weapon identity & ledger (HMAC-signed items, epoch invalidation, history, atomic durable commits, backup/corruption
handling) · curse (inventory/drop/hopper/frame/portal/void rules, keepInventory + grave-plugin recovery, combat-log) ·
Blood Oath · Rupture (SAT geometry, true damage through the real damage event, central bleed scheduler, shields, LOS) ·
Last King's Fury + Exhaustion (transient namespaced attribute modifiers, exact post-mitigation reduction, cleanup on
death/quit/world change/disable) · spectral armor (per-viewer pack/vanilla) · central ability dispatcher + alias config ·
Bloodiron (generation, Silk Touch, signed items, anti-dupe) · Lost Forge (locate, plan-before-build, throttled build,
resume, rebuild, protection) · ritual state machine (5x15, public, fail/retry, restart handling, mob AI/containment) ·
reward + first-pickup claim + announcement · admin tooling + audit log · FX system with density/budget/TPS throttle ·
resource pack with vanilla fallbacks.

## Deviations / interpretations (documented, configurable)
* First Rage loss happens exactly when decay starts (2.5 s), then every 1 s.
* Fury's 10 % reduction is applied before absorption hearts.
* Cooldowns belong to the **blade** (passing it on does not reset them).
* `end-on-world-change: true` ends Fury (with exhaustion) when the holder changes world.
* Rupture's true damage is delivered as a magic-type source with only the attacker attached (no knockback); plugins see
  cause `MAGIC`, and the death message is overridden to "cleaved apart / bled out".
* Ritual mobs do not damage each other; mob drops/XP are off by default.
* Server restart mid-trial -> `INTERRUPTED`: the altar resumes the same wave **for free** (ore was already paid).
* The blade cannot be placed in an Allay/armor stand/item frame either; Bloodiron items cannot be placed.

## Known limitations
* Not exercised with a real client or several players (see TEST_PLAN section C). Visual quality is unproven.
* Art is procedural placeholder-grade; sword/armor model transforms and the equipment asset are unverified in-client.
* Own-third-person view of spectral armor is off by default (client-side slot rewrite risk).
* Ore only appears in **newly generated chunks** (existing terrain: `bloodiron scatter`, loaded chunks only). Measured
  87 % of diamond frequency; raise `small-veins-per-chunk` to taste. Veins never cross chunk borders.
* Bloodiron identity = chunk-tracked positions of the host block; WorldEdit-style edits can leave stale records
  (validated by block type on use).
* Retexturing `nether_gold_ore` affects natural Nether gold ore too.
* The forge has no in-game discovery hint; admins/owners decide how to share the coordinates.
* PvP must be enabled in the forge world; one forge per server; one world.
* Bleed is not persisted across a restart (frozen while the target is offline).
* No shield durability damage from Rupture.
* Interaction with external region/PvP plugins relies on event cancellation and is untested.
* No MockBukkit-level integration tests; Bukkit-dependent logic is covered only by the live-server checks above.

## Remaining / ideas
Live multiplayer QA pass; hand-made art; optional discovery hints (compass / rumour); public `/hunt`-style tracker;
WorldGuard flag integration; persistence of bleed.
