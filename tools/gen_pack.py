"""Generates the Kingdoms of Ash resource pack textures (pure stdlib, no PIL needed).
Run:  python tools/gen_pack.py      (writes into ./resourcepack)
"""
import json, os, random, struct, zlib

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'resourcepack')


def png(path, w, h, px):
    """px: dict (x,y)->(r,g,b[,a]); everything else transparent."""
    os.makedirs(os.path.dirname(path), exist_ok=True)
    raw = bytearray()
    for y in range(h):
        raw.append(0)
        for x in range(w):
            c = px.get((x, y), (0, 0, 0, 0))
            if len(c) == 3:
                c = c + (255,)
            raw.extend(c)

    def chunk(t, d):
        c = struct.pack('>I', len(d)) + t + d
        return c + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)

    data = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 6, 0, 0, 0)) \
        + chunk(b'IDAT', zlib.compress(bytes(raw), 9)) + chunk(b'IEND', b'')
    with open(path, 'wb') as f:
        f.write(data)


def jwrite(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8') as f:
        json.dump(obj, f, indent=2)


def mix(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


OUTLINE = (12, 10, 14)
STEEL_D, STEEL_M, STEEL_L = (52, 55, 64), (104, 108, 120), (176, 180, 192)
CRIMSON, BRIGHT, BLOOD, EMBER = (176, 20, 31), (230, 44, 44), (110, 6, 12), (255, 122, 36)
IRON_BLACK = (30, 28, 34)


# ------------------------------------------------------------------ sword (32x32, drawn tip up-right)
def sword():
    px = {}

    def put(x, y, c):
        if 0 <= x < 32 and 0 <= y < 32:
            px[(x, y)] = c

    def diag(i, off):  # position along the blade axis i (0 = pommel end) and perpendicular offset
        return 1 + i + off, 30 - i + off

    # blade i = 9..30, 4 px wide with a fuller; tip tapers
    for i in range(9, 31):
        taper = 0 if i < 26 else (i - 25)
        for off in range(-2, 3):
            if abs(off) + taper > 2:
                continue
            x, y = 1 + i + (off + 0) // 2 + (1 if off > 0 else 0) * 0, 30 - i
            # use true diagonal stripes: shift x by off, y by -off
            x, y = 1 + i + off, 30 - i + off
            if abs(off) == 2:
                c = OUTLINE
            elif off == 0:
                c = STEEL_D        # fuller (dark groove)
            elif off < 0:
                c = STEEL_L        # lit edge
            else:
                c = STEEL_M
            if i > 12 and off == 0 and i % 4 == 0:
                c = BRIGHT         # crimson rune glint in the fuller
            put(x, y, c)
    # tip
    put(1 + 31, 30 - 31, STEEL_L)
    put(1 + 32 - 1, 30 - 32 + 1, OUTLINE)
    # crossguard centred at i=8, perpendicular to the blade (runs along (+1,+1))
    for k in range(-7, 8):
        thick = 2 if abs(k) < 5 else 1
        for t in range(thick):
            x, y = 1 + 8 + k, 30 - 8 + k + t - (1 if abs(k) > 4 else 0)
            edge = abs(k) == 7 or t == thick - 1 and abs(k) > 4
            put(x, y, OUTLINE if abs(k) == 7 else (IRON_BLACK if t == 0 else (60, 56, 64)))
    for k in (-6, -5, 5, 6):  # horn tips curve up
        put(1 + 8 + k, 30 - 8 + k - 2, IRON_BLACK)
    # crimson gem at the centre
    for dx, dy in ((0, 0), (1, 0), (0, 1), (1, 1)):
        put(1 + 8 + dx, 30 - 8 + dy, BRIGHT if (dx, dy) == (0, 0) else CRIMSON)
    put(1 + 8, 30 - 8 - 1, EMBER)
    # grip i = 2..7 wrapped leather
    for i in range(2, 8):
        for off in (-1, 0, 1):
            x, y = 1 + i + off, 30 - i + off
            c = (58, 30, 28) if (i % 2 == 0) else (36, 20, 20)
            if abs(off) == 1:
                c = OUTLINE
            put(x, y, c)
    # pommel: blood-red orb
    for dx, dy in ((0, 0), (1, 0), (0, 1), (1, 1), (-1, 0), (0, -1), (2, 0), (0, 2)):
        put(1 + 0 + dx + 1, 30 - 0 + dy - 1, CRIMSON if (dx + dy) % 2 else BLOOD)
    put(2, 29, EMBER)
    return px


# ------------------------------------------------------------------ ore (16x16)
def ore():
    rnd = random.Random(1337)
    px = {}
    for y in range(16):
        for x in range(16):
            n = rnd.random()
            base = (46, 44, 50) if n < 0.5 else (38, 36, 42) if n < 0.8 else (58, 56, 62)
            px[(x, y)] = base
    # riveted dark-iron plates
    for (cx, cy) in ((3, 3), (11, 4), (6, 11), (13, 12), (2, 9)):
        for dx in range(-2, 3):
            for dy in range(-2, 3):
                if (cx + dx, cy + dy) in px and abs(dx) + abs(dy) < 4:
                    px[(cx + dx, cy + dy)] = mix((70, 72, 82), (96, 100, 112), rnd.random())
    # crimson rune veins
    veins = [(2, 14), (3, 13), (4, 12), (4, 11), (5, 10), (6, 9), (7, 9), (8, 8), (9, 7), (9, 6), (10, 5), (11, 4), (12, 3), (13, 2)]
    veins += [(5, 4), (6, 4), (7, 5), (7, 6), (3, 6), (2, 7), (12, 10), (11, 11), (10, 12), (9, 13)]
    for p in veins:
        px[p] = CRIMSON
    for p in veins[::3]:
        px[p] = BRIGHT
    for p in ((8, 8), (4, 11), (11, 4), (10, 12)):
        px[p] = EMBER
    return px


# ------------------------------------------------------------------ spectral armor layers (64x32)
def armor(seed):
    rnd = random.Random(seed)
    px = {}
    for y in range(32):
        for x in range(64):
            n = rnd.random()
            c = (22, 20, 26) if n < 0.55 else (32, 30, 38) if n < 0.9 else (50, 48, 58)
            # plate seams
            if x % 8 == 0 or y % 8 == 0:
                c = (12, 10, 14)
            px[(x, y)] = c
    # crimson runes: short strokes inside the plates
    for gy in range(1, 32, 8):
        for gx in range(1, 64, 8):
            if rnd.random() < 0.65:
                kind = rnd.randint(0, 3)
                pts = [(gx + 2, gy + 1), (gx + 2, gy + 2), (gx + 2, gy + 3), (gx + 3, gy + 3), (gx + 4, gy + 3)] if kind == 0 else \
                      [(gx + 1, gy + 2), (gx + 2, gy + 2), (gx + 3, gy + 2), (gx + 3, gy + 3), (gx + 3, gy + 4)] if kind == 1 else \
                      [(gx + 2, gy + 1), (gx + 3, gy + 2), (gx + 4, gy + 3), (gx + 3, gy + 4), (gx + 2, gy + 5)] if kind == 2 else \
                      [(gx + 1, gy + 1), (gx + 2, gy + 2), (gx + 3, gy + 3), (gx + 4, gy + 4), (gx + 4, gy + 2)]
                for i, p in enumerate(pts):
                    px[p] = BRIGHT if i == len(pts) // 2 else CRIMSON
    return px


def main():
    png(os.path.join(ROOT, 'assets/kingdomsofash/textures/item/oathbreaker.png'), 32, 32, sword())
    png(os.path.join(ROOT, 'assets/minecraft/textures/block/nether_gold_ore.png'), 16, 16, ore())
    base = 'assets/kingdomsofash/textures/entity/equipment'
    png(os.path.join(ROOT, base, 'humanoid/spectral_king.png'), 64, 32, armor(11))
    png(os.path.join(ROOT, base, 'humanoid_leggings/spectral_king.png'), 64, 32, armor(23))

    jwrite(os.path.join(ROOT, 'pack.mcmeta'), {
        "pack": {
            "description": "Kingdoms of Ash - Oathbreaker, Bloodiron and the Last King's armor",
            "min_format": 97,
            "max_format": 97
        }
    })
    # item definition: only items carrying custom_model_data string "oathbreaker" switch model; everything else stays vanilla
    jwrite(os.path.join(ROOT, 'assets/minecraft/items/netherite_sword.json'), {
        "model": {
            "type": "minecraft:select",
            "property": "minecraft:custom_model_data",
            "index": 0,
            "cases": [{"when": "oathbreaker", "model": {"type": "minecraft:model", "model": "kingdomsofash:item/oathbreaker"}}],
            "fallback": {"type": "minecraft:model", "model": "minecraft:item/netherite_sword"}
        }
    })
    # oversized in hand (a greatsword), normal size in GUI
    jwrite(os.path.join(ROOT, 'assets/kingdomsofash/models/item/oathbreaker.json'), {
        "parent": "minecraft:item/handheld",
        "textures": {"layer0": "kingdomsofash:item/oathbreaker"},
        "display": {
            "thirdperson_righthand": {"rotation": [0, -90, 55], "translation": [0, 4.5, 0.5], "scale": [1.5, 1.5, 0.85]},
            "thirdperson_lefthand": {"rotation": [0, 90, -55], "translation": [0, 4.5, 0.5], "scale": [1.5, 1.5, 0.85]},
            "firstperson_righthand": {"rotation": [0, -90, 25], "translation": [1.13, 3.2, 1.13], "scale": [1.1, 1.1, 0.68]},
            "firstperson_lefthand": {"rotation": [0, 90, -25], "translation": [1.13, 3.2, 1.13], "scale": [1.1, 1.1, 0.68]},
            "ground": {"translation": [0, 2, 0], "scale": [0.9, 0.9, 0.9]},
            "gui": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]},
            "fixed": {"rotation": [0, 180, 0], "scale": [1, 1, 1]}
        }
    })
    jwrite(os.path.join(ROOT, 'assets/kingdomsofash/equipment/spectral_king.json'), {
        "layers": {
            "humanoid": [{"texture": "kingdomsofash:spectral_king"}],
            "humanoid_leggings": [{"texture": "kingdomsofash:spectral_king"}]
        }
    })
    print('resource pack generated in', os.path.abspath(ROOT))


if __name__ == '__main__':
    main()
