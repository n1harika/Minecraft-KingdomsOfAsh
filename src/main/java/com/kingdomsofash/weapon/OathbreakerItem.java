package com.kingdomsofash.weapon;

import com.kingdomsofash.config.Settings;
import com.kingdomsofash.util.Keys;
import com.kingdomsofash.util.Signer;
import com.kingdomsofash.util.Text;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.CustomModelData;
import net.kyori.adventure.text.Component;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemRarity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.UUID;

/** Factory and verifier for the signed Oathbreaker item. */
public final class OathbreakerItem {
    public record Identity(UUID id, long epoch) {
    }

    private final Settings s;
    private final Signer signer;

    public OathbreakerItem(Settings s, Signer signer) {
        this.s = s;
        this.signer = signer;
    }

    public void settings(Settings s2) {
        // Settings is a live object that reloads in place; nothing cached here.
    }

    private static String payload(UUID id, long epoch) {
        return "oath|" + id + "|" + epoch;
    }

    public ItemStack create(UUID id, long epoch) {
        ItemStack item = new ItemStack(s.weaponMaterial);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Text.item("<gradient:#e0242f:#7a0610><bold>OATHBREAKER</bold></gradient>"));
        meta.lore(List.<Component>of(
                Text.item("<dark_gray>Greatsword of the Last King"),
                Text.item(""),
                Text.item("<gray>Forged in the <dark_red>Lost Forge<gray>, sworn to blood."),
                Text.item("<gray>Its bearer must <red>carry<gray> it, always."),
                Text.item(""),
                Text.item("<dark_red>Blood Oath <dark_gray>- <gray>every 2 hits build <red>Rage <gray>(max 5)"),
                Text.item("<red>/ability1 <dark_gray>Rupture <gray>(2 Rage)"),
                Text.item("<red>/ultimate <dark_gray>Last King's Fury <gray>(3+ Rage)"),
                Text.item(""),
                Text.item("<dark_gray>There is only one. Hunt its wielder.")));
        meta.setUnbreakable(true);
        meta.setEnchantmentGlintOverride(true);
        meta.setRarity(ItemRarity.EPIC);
        meta.addAttributeModifier(Attribute.ATTACK_DAMAGE, new AttributeModifier(Keys.WEAPON_DAMAGE,
                s.weaponAttackDamage, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));
        meta.addAttributeModifier(Attribute.ATTACK_SPEED, new AttributeModifier(Keys.WEAPON_SPEED,
                s.weaponAttackSpeedModifier, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));
        meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE);
        var pdc = meta.getPersistentDataContainer();
        pdc.set(Keys.OATH_ID, PersistentDataType.STRING, id.toString());
        pdc.set(Keys.OATH_EPOCH, PersistentDataType.LONG, epoch);
        pdc.set(Keys.OATH_SIG, PersistentDataType.STRING, signer.sign(payload(id, epoch)));
        item.setItemMeta(meta);
        // The resource pack matches this string; without the pack the vanilla netherite sword is shown.
        item.setData(DataComponentTypes.CUSTOM_MODEL_DATA,
                CustomModelData.customModelData().addString(s.customModelString).build());
        return item;
    }

    /** @return identity if the stack carries a valid signature (does NOT check the ledger epoch), else null. */
    public Identity identify(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return null;
        var pdc = item.getPersistentDataContainer();
        String id = pdc.get(Keys.OATH_ID, PersistentDataType.STRING);
        if (id == null) return null;
        Long epoch = pdc.get(Keys.OATH_EPOCH, PersistentDataType.LONG);
        String sig = pdc.get(Keys.OATH_SIG, PersistentDataType.STRING);
        if (epoch == null || sig == null) return null;
        try {
            UUID u = UUID.fromString(id);
            return signer.verify(payload(u, epoch), sig) ? new Identity(u, epoch) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** True if the stack merely CLAIMS to be Oathbreaker (has our tag), valid or not. Used to purge fakes. */
    public boolean claimsToBeOathbreaker(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getPersistentDataContainer().has(Keys.OATH_ID, PersistentDataType.STRING);
    }
}
