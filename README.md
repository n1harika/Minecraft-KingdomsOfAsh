# Kingdoms of Ash

A dark-medieval Paper plugin built around **Oathbreaker**, a one-of-a-kind greatsword won in the **Lost Forge** (a
public, PvP-open, five-wave underground trial) and carried by a hunted wielder.

* Target: **Paper 26.3** (build 206+), **Java 25**. Verified against the real 26.3 API/server (see `docs/STATUS.md`).
* Plugin JAR: `build/libs/KingdomsOfAsh-1.0.0.jar` · Resource pack: `build/resourcepack/KingdomsOfAsh-ResourcePack.zip`

## Build

```
./gradlew build          # jar + resource-pack zip (+ .sha1) + 27 unit tests
python tools/gen_pack.py # regenerate pack textures (only if you change them)
```
JDK 25 is required (`JAVA_HOME`). Behind a proxy, put the `systemProp.https.proxyHost/Port` lines in
`~/.gradle/gradle.properties`.

## Install

1. Drop the JAR into `plugins/`. Start the server once, then edit `plugins/KingdomsOfAsh/config.yml` if desired.
2. **PvP must be enabled** in the world containing the forge (`pvp=true`), otherwise the arena cannot work as designed
   (the plugin logs a warning).
3. The forge generates itself shortly after the first start (once per server). Coordinates: `/oathbreaker forge info`.
4. Optional but recommended: host the resource pack (see `docs/VISUAL_EFFECTS.md`) and set `resource-pack.url` + `sha1`.
5. Death behaviour: Oathbreaker **ignores keepInventory** (config `weapon.drop-on-keep-inventory`). Grave / death-chest
   plugins that capture drops are detected and recovered automatically (epoch bump, one fresh blade is dropped).

Files created in `plugins/KingdomsOfAsh/`: `config.yml`, `data/state.json` (+`.bak`; ledger, forge, ritual, cooldowns),
`secret.key` (HMAC key that signs items - **back it up with state.json**), `audit.log`.

## Player commands

| Command | Effect |
|---|---|
| `/ability1` | Rupture (only if Oathbreaker is in the main hand) |
| `/ultimate` | Last King's Fury (same) |

Both go through one dispatcher (`AbilityDispatcher`); another weapon can register its own `WeaponAbilities` and will only
run when it is the held item. If the labels collide with another plugin use `/kingdomsofash:ability1` or add
`commands.ability1-aliases` / `ultimate-aliases` in the config.

## Admin: `/oathbreaker` (permission `kingdomsofash.admin`, default op)

`status`, `holder`, `history [n]`, `forge [info|tp|generate|rebuild|relocate]`, `ritual [status|reset|fail]`, `mobs`,
`cooldowns`, `give <player>`, `recover <player|altar|here>`, `verify`, `bloodiron [give|scatter|stats]`,
`fx <rupture|hit|bleed|shield|fury|expire|exhaust|ritual|wave N|reward|fail|announce|ambient|beam|rage>`, `audit [n]`,
`reload`. State-changing subcommands need a trailing `confirm` and are written to `audit.log`.

`give` / `recover` never create a second legitimate blade: they bump the item **epoch**, which invalidates every older
copy anywhere in the world, then issue exactly one new item.

## Gameplay summary (defaults = specification values)

* **Blood Oath**: every 2 valid consecutive melee hits = 1 Rage (max 5). After 2.5 s without a hit one Rage is lost,
  then one per second. Being hit never costs Rage. *Valid hit*: legitimate holder, main hand, `ENTITY_ATTACK`, event not
  cancelled, final damage > 0, living non-armor-stand target, once per (attacker,target,tick).
* **Rupture** (`/ability1`): 2 Rage, 45 s, 6-wide x 3-long oriented rectangle (SAT test against the target's bounding
  box), 3 hearts true damage + 1 heart / 2 s for 8 s (refreshes, never stacks), shields block slash and bleed, rage and
  cooldown consumed even on a miss, never consumed on an invalid/cancelled cast.
* **Last King's Fury** (`/ultimate`): >=3 Rage (all consumed), 15 s, 150 s cooldown, +25 % melee damage, +15 % attack
  speed, +10 % move speed, no fall damage, -10 % damage taken **after** armor/enchantments/resistance. Then 5 s
  *King's Exhaustion*: -15 % melee damage, -10 % move speed. Cooldowns are bound to the blade and persist.
* **Bloodiron Ore**: diamond-equivalent veins in newly generated chunks, **Silk Touch pickaxe only**; 64 are consumed per
  attempt. Normal mining drops iron nuggets + XP (configurable). Items are HMAC-signed; they cannot be placed or smelted.
* **Lost Forge**: one per server, inside +/-2000 of spawn, underground, reached by a lined stairway.
  Right-click the altar anvil with 64 Bloodiron to begin. 5 waves x 15 mobs (zombie/skeleton/creeper/enderman, weighted),
  next wave only when the previous is dead, public entry + PvP, trial fails when no living player remains in the arena.
  Failure consumes the ore and starts a 30 min retry cooldown. After wave 5 the blade physically appears over the altar;
  the **first pickup** claims it and triggers the server-wide announcement.
* **The curse**: the blade cannot be stored in any container/ender chest/item frame, dropped, or thrown; it drops only
  when the holder dies (even with keepInventory) or combat-logs. Pickup transfers ownership instantly; the original
  claimant is kept forever.

More: `docs/VISUAL_EFFECTS.md`, `docs/TEST_PLAN.md`, `docs/STATUS.md` (done / limitations / remaining).
