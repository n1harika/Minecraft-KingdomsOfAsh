package com.kingdomsofash.util;

import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;

/** All persistent-data keys used by the plugin. */
public final class Keys {
    public static NamespacedKey OATH_ID;
    public static NamespacedKey OATH_EPOCH;
    public static NamespacedKey OATH_SIG;
    public static NamespacedKey OATH_ENTITY;

    public static NamespacedKey ORE_ITEM;
    public static NamespacedKey ORE_SIG;
    public static NamespacedKey ORE_CHUNK;

    public static NamespacedKey RITUAL_MOB;
    public static NamespacedKey RITUAL_WAVE;

    public static NamespacedKey FURY_DAMAGE_ATTACK_SPEED;
    public static NamespacedKey FURY_MOVE;
    public static NamespacedKey EXHAUST_MOVE;
    public static NamespacedKey MOB_HEALTH;
    public static NamespacedKey MOB_DAMAGE;
    public static NamespacedKey WEAPON_DAMAGE;
    public static NamespacedKey WEAPON_SPEED;
    public static NamespacedKey MOB_FOLLOW;

    private Keys() {
    }

    public static void init(Plugin p) {
        OATH_ID = new NamespacedKey(p, "oath_id");
        OATH_EPOCH = new NamespacedKey(p, "oath_epoch");
        OATH_SIG = new NamespacedKey(p, "oath_sig");
        OATH_ENTITY = new NamespacedKey(p, "oath_entity");
        ORE_ITEM = new NamespacedKey(p, "bloodiron_item");
        ORE_SIG = new NamespacedKey(p, "bloodiron_sig");
        ORE_CHUNK = new NamespacedKey(p, "bloodiron_blocks");
        RITUAL_MOB = new NamespacedKey(p, "ritual_encounter");
        RITUAL_WAVE = new NamespacedKey(p, "ritual_wave");
        FURY_DAMAGE_ATTACK_SPEED = new NamespacedKey(p, "fury_attack_speed");
        FURY_MOVE = new NamespacedKey(p, "fury_move_speed");
        EXHAUST_MOVE = new NamespacedKey(p, "exhaustion_move_speed");
        MOB_HEALTH = new NamespacedKey(p, "ritual_health");
        MOB_DAMAGE = new NamespacedKey(p, "ritual_damage");
        WEAPON_DAMAGE = new NamespacedKey(p, "oathbreaker_damage");
        WEAPON_SPEED = new NamespacedKey(p, "oathbreaker_speed");
        MOB_FOLLOW = new NamespacedKey(p, "ritual_follow");
    }
}
