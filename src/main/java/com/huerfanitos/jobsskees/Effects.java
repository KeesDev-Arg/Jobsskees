package com.huerfanitos.jobsskees;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Random;

/** Efectos que actuan durante el juego, segun los talentos activos del jugador. */
public final class Effects implements Listener {

    private final JobsSkeesPlugin plugin;
    private final Random rng = new Random();

    public Effects(JobsSkeesPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * drop_bonus: cada efecto activo tiene su propia probabilidad de sumar 1 objeto extra al drop
     * principal del bloque roto. No actua con Toque de seda ni en creativo.
     */
    @EventHandler(ignoreCancelled = true)
    public void onDrop(BlockDropItemEvent e) {
        Player p = e.getPlayer();
        if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) {
            return;
        }
        JobsSkeesPlugin.Active act = plugin.active(p);
        if (act == null || act.drops.isEmpty()) {
            return;
        }
        ItemStack tool = p.getInventory().getItemInMainHand();
        if (tool.containsEnchantment(Enchantment.SILK_TOUCH)) {
            return;
        }
        List<Item> items = e.getItems();
        if (items.isEmpty()) {
            return;
        }
        Material block = e.getBlockState().getType();

        for (Tree.Effect ef : act.drops) {
            if (!ef.matchesBlock(block)) {
                continue;
            }
            if (rng.nextDouble() >= ef.chance) {
                continue;
            }
            Item target = null;
            for (Item it : items) {
                if (ef.matchesItem(it.getItemStack().getType())) {
                    target = it;
                    break;
                }
            }
            if (target == null) {
                continue;
            }
            ItemStack stack = target.getItemStack();
            stack.setAmount(stack.getAmount() + 1);
            target.setItemStack(stack);
        }
    }

    /** durability_save: probabilidad de que un uso no gaste durabilidad del objeto. */
    @EventHandler(ignoreCancelled = true)
    public void onItemDamage(PlayerItemDamageEvent e) {
        JobsSkeesPlugin.Active act = plugin.active(e.getPlayer());
        if (act == null || act.durability.isEmpty()) {
            return;
        }
        Material type = e.getItem().getType();
        for (Tree.Effect ef : act.durability) {
            if (!ef.matchesItem(type)) {
                continue;
            }
            if (rng.nextDouble() < ef.chance) {
                e.setCancelled(true);
                return;
            }
        }
    }
}
