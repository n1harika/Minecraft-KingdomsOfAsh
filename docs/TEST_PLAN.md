# Test plan

## A. Automated (run: `./gradlew test`) - 27 tests, all passing at time of writing
`PureLogicTest` (Rage cap / decay / chain-break / spend, Rupture geometry front/behind/width/bounding-box/yaw/vertical,
Fury "10 -> 9" and absorption order, wave = exactly 15 with caps, ledger single claim / claimant survives transfers /
recovery invalidates old copies / admin identity), `StateStoreTest` (round trip, backup fallback, refuse-empty-start),
`ForgeBlueprintTest` (deterministic, altar anvil on plinth, enclosed hall, gate + stairway connected in all 4
directions, runes, intact+broken pillars).

## B. Executed against a real Paper 26.3 server (headless, RCON) - done
* Plugin loads/enables/disables cleanly; config + state created.
* Forge located inside +/-2000 of spawn, planned+persisted before building, built (29 473 blocks), **not regenerated after
  restart**, `forge rebuild confirm` idempotent; block data names valid on 26.3 (this caught `chain` -> `iron_chain`).
* Structure spot-checked in the world (anvil on plinth, chain, rune bricks, floor/gate air).
* Bloodiron frequency on 324 fresh chunks: **21.4 / chunk vs vanilla diamond 24.7 / chunk (~87 %)**.
* Admin commands (`status`, `forge info`, `ritual status`, `cooldowns`, `forge rebuild`, `bloodiron stats`).

## C. REQUIRES a live client / several players - NOT done (do not treat as verified)
Run with 2-3 players (one op). `/oathbreaker bloodiron give <you> 64`, `/oathbreaker forge tp`.

1. **Ritual**: right-click altar without ore -> message, nothing consumed. With 64 -> ore consumed, intro, wave 1.
   Exactly 15 mobs per wave (`/oathbreaker mobs`), next wave only after all dead, last foe glows, boss bar correct.
2. **Containment**: Endermen cannot teleport out of the 20-block hall; creepers do not damage walls; pistons/buckets/TNT
   cannot alter the structure; ordinary mobs entering are never counted.
3. **Public/PvP**: second player joins mid-wave and can fight the first; trial continues while any living player is
   inside; kill everyone / leave -> trial FAILS, ore stays consumed, 30 min cooldown, mobs removed.
4. **Reward**: blade hovers at the altar after wave 5; two players grab simultaneously -> exactly one claim, one
   announcement with the right name; restart server during `REWARD_PENDING` -> exactly one blade afterwards.
5. **Curse**: try chest, shulker, ender chest, hopper, dropper, item frame, shift-click, number-key, offhand swap,
   drag, Q, ctrl-Q, creative drop, bundle -> all rejected. Die (also with keepInventory) -> blade drops, killer picks it
   up -> ownership moves, `claimant` unchanged. Quit within 15 s of PvP damage -> blade drops.
6. **Duplicates**: `give` a second copy via creative/`/give` with copied NBT -> purged. `recover` -> old copy dies.
7. **Rupture**: width/length visible vs. real hit area (stand just inside/outside), behind you not hit, shield stops damage
   and bleed, bleed doesn't stack, armor/Protection/Resistance don't reduce it, kill message shows "bled out".
8. **Fury**: numbers (use `/attribute` readout and damage logs), no knockback bonus, fall damage gone, -10 % after armor,
   exhaustion, cleanup on death/quit/world change (`/attribute <p> minecraft:movement_speed get`).
9. **Visuals**: everything in `docs/VISUAL_EFFECTS.md`: slash direction/readability, bleed distinguishable, Fury armor
   seen by a *second* player (with and without the pack), first person unobstructed, forge atmosphere, wave looks,
   reward climax, particle load with 10+ players (watch `/tps`, tune `effects.*`).
10. **Compat**: WorldGuard-style regions / PvP flags (cancelled damage must also cancel Rupture/bleed), grave plugins.
