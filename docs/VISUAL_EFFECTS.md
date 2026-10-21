# Visual & audio design guide

## Palette and motion
Crimson `#B0141F`, bright red, blood `#70040A`, charcoal `#1A1618`, steel, with a restrained ember orange. Effects are
directional (arcs, rings, helixes, rising embers) and sent **only to players within `effects.effect-distance`**.

| Moment | What happens |
|---|---|
| Rupture | Rune flare at the blade, 5-tick diagonal cleave drawn as a semicircle whose radius = range (so its width *is* the hit width), bright-red leading edge / blood body / charcoal soot trail, sweep spark, 4-layer swing sound. Impact: red burst + redstone-block crumbs + crit sound. Bleed: falling blood droplets (distinct from fire/poison) + wet pulse every tick. Shield: steel sparks + shield/anvil/chain sounds. No-Rage / cooldown: quiet action-bar + low click. |
| Blood Oath | Action-bar `ᚱ ◆◆◇◇◇` only on change; rune flare + rising pitch on gain; flame burst at max; smoke on loss; ember at the hand scaled by Rage. |
| Fury | Sonic-boom pulse, expanding shockwave ring, rotating rune circles at the feet, smoke/embers, bell + armor-forging + roar + thunder layers (10 ticks, no lock-out). Aura while active (light). Boss bar countdown. Expiry: collapsing ring + blackstone crumbs. Exhaustion: ash, ink and grey dust only (no crimson). |
| Forge | Ambient embers/runes only when a player is near; altar "breathes" on approach. Ritual start: crimson pillar + ring + bell. Waves 1-5 each have a different spawn look (fire / souls / lightning+sparks / crimson spores+dragon breath / ominous+lightning). Wave end: inward ring + chime. Reward: counter-rotating helixes, rising pitch, then lightning + sonic boom + totem burst as the blade appears. Dropped/unclaimed blade: crimson particle pillar. |
| Announcement | Title `☠ A LEGEND HAS AWAKENED ☠`, bell/wither/thunder/dragon layers, timed chat lines with the claimant's name. |

## Performance safeguards
`effects.particle-density` (multiplier), `sound-volume`, `effect-distance`, a per-tick **decorative budget**
(essential combat feedback bypasses it), and an automatic TPS throttle (halves, then quarters density below the
configured TPS). Ambient forge effects run only with a player in range.

## Resource pack (optional, with vanilla fallbacks)
Build: `./gradlew resourcePack` -> `build/resourcepack/KingdomsOfAsh-ResourcePack.zip` (+ `.sha1`). Host it anywhere,
set `resource-pack.url` and `sha1` (the plugin sends it on join), or put it in `server.properties`.
Pack format used: **97** (from the 26.3 `version.json`).

| Feature | With pack | Without pack (fallback) |
|---|---|---|
| Oathbreaker | Custom greatsword model (oversized in hand), selected by `custom_model_data` string `oathbreaker` | Vanilla netherite sword + enchant glint + name/lore (no missing-model risk) |
| Bloodiron Ore | Retextures `nether_gold_ore`: dark iron plates with crimson rune veins | Vanilla nether gold ore (red netherrack with gold flecks) - already unlike redstone ore |
| Spectral armor | `kingdomsofash:spectral_king` equipment asset: black plate, crimson runes | Black netherite with a redstone trim (per viewer: AUTO uses the pack look only for clients that loaded it) |

### Honest technical limits
* **Spectral armor** is client-side equipment sent with `Player#sendEquipmentChange`; the wearer's real armor is never
  changed. Your own first-person view is never obstructed (the client does not draw your armor there). The wearer's
  *own* third-person view is **off by default** (`show-to-self`) because sending equipment about yourself also rewrites
  your client's armor slots until the next sync. Others always see it.
* A plain potion effect cannot render armor; no claim is made that it does.
* `nether_gold_ore` is retextured globally when the pack is active (also natural Nether gold ore). Identity is tracked
  server-side per chunk, never by texture.
* The sword/armor/ore art is **procedurally generated** (`tools/gen_pack.py`) and meant to be replaced by hand-made art.
  None of it has been seen in a running client yet - see `docs/TEST_PLAN.md`.
