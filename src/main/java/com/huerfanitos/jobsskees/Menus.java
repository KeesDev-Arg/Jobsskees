package com.huerfanitos.jobsskees;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Interfaz de JobsSkees: menu principal (un icono por trabajo) y menu del arbol de cada trabajo. */
public final class Menus implements Listener {

    private static final int[] MAIN_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};
    private static final int SLOT_BACK = 45;
    private static final int SLOT_INFO = 49;
    private static final int SLOT_RESET = 53;
    private static final long CONFIRM_MS = 10_000L;

    /** Marca los inventarios de este plugin y guarda a que corresponde cada casillero. */
    public static final class Holder implements InventoryHolder {
        public final String kind;      // "main" o "tree"
        public final String treeId;
        public final Map<Integer, String> slotTree = new HashMap<>();
        public final Map<Integer, String> slotNode = new HashMap<>();
        private Inventory inventory;

        public Holder(String kind, String treeId) {
            this.kind = kind;
            this.treeId = treeId;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        public void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }
    }

    private final JobsSkeesPlugin plugin;
    private final Map<UUID, String> resetPending = new HashMap<>();   // jugador -> "arbol:momento"

    public Menus(JobsSkeesPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------
    // Utilidades de items
    // ------------------------------------------------------------------

    private Component c(String s) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(s).decoration(TextDecoration.ITALIC, false);
    }

    private ItemStack make(Material m, String name, List<String> lore, boolean glow) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        if (meta == null) {
            return it;
        }
        meta.displayName(c(name));
        List<Component> l = new ArrayList<>();
        for (String s : lore) {
            l.add(c(s));
        }
        meta.lore(l);
        if (glow) {
            meta.setEnchantmentGlintOverride(true);
        }
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE);
        it.setItemMeta(meta);
        return it;
    }

    private ItemStack filler() {
        return make(Material.GRAY_STAINED_GLASS_PANE, " ", new ArrayList<>(), false);
    }

    private void fill(Inventory inv) {
        ItemStack f = filler();
        for (int i = 0; i < inv.getSize(); i++) {
            inv.setItem(i, f);
        }
    }

    // ------------------------------------------------------------------
    // Menu principal
    // ------------------------------------------------------------------

    public void openMain(Player p) {
        Holder h = new Holder("main", "");
        Inventory inv = Bukkit.createInventory(h, 27, Component.text("JobsSkees - Talentos"));
        h.setInventory(inv);
        fill(inv);

        PlayerData d = plugin.data(p);
        int i = 0;
        for (Tree t : plugin.trees()) {
            if (i >= MAIN_SLOTS.length) {
                break;
            }
            int level = plugin.level(p, t);
            int total = plugin.totalPoints(level);
            int spent = plugin.spent(d, t);
            List<String> lore = new ArrayList<>();
            for (String line : t.description) {
                lore.add(line);
            }
            lore.add(" ");
            lore.add("&7Nivel del trabajo: &f" + level);
            lore.add("&7Puntos: &f" + total + " &8| &7Gastados: &f" + spent
                    + " &8| &7Libres: &a" + Math.max(0, total - spent));
            lore.add(" ");
            lore.add("&eClick para abrir el arbol");
            int slot = MAIN_SLOTS[i++];
            inv.setItem(slot, make(t.icon, t.display, lore, level > 0));
            h.slotTree.put(slot, t.id);
        }
        p.openInventory(inv);
    }

    // ------------------------------------------------------------------
    // Menu de un arbol
    // ------------------------------------------------------------------

    public void openTree(Player p, Tree t) {
        Holder h = new Holder("tree", t.id);
        Inventory inv = Bukkit.createInventory(h, 54, c(t.display + " &8- Talentos"));
        h.setInventory(inv);
        render(p, t, h, inv);
        p.openInventory(inv);
    }

    private void render(Player p, Tree t, Holder h, Inventory inv) {
        fill(inv);
        h.slotNode.clear();

        PlayerData d = plugin.data(p);
        int level = plugin.level(p, t);
        int total = plugin.totalPoints(level);
        int spent = plugin.spent(d, t);
        int available = total - spent;
        List<String> unlockedIds = d.unlocked.getOrDefault(t.id, new ArrayList<>());
        List<Tree.Node> activeList = plugin.activeNodes(d, t, total);
        boolean deficit = available < 0;

        for (Tree.Node n : t.nodes.values()) {
            int slot = n.row * 9 + n.col;
            boolean isUnlocked = unlockedIds.contains(n.id);
            boolean isActive = activeList.contains(n);

            List<String> lore = new ArrayList<>();
            for (String line : n.description) {
                lore.add(line);
            }
            lore.add(" ");
            lore.add("&7Costo: &f" + n.cost + " &7punto(s)");

            String name;
            boolean glow = false;
            if (isUnlocked && isActive) {
                name = "&a" + n.name;
                lore.add("&aDesbloqueado");
                glow = true;
            } else if (isUnlocked) {
                name = "&c" + n.name;
                lore.add("&cSuspendido: no alcanzan los puntos");
                lore.add("&7(se reactiva al recuperar nivel)");
            } else {
                String missing = missingRequirements(t, n, unlockedIds);
                if (!missing.isEmpty()) {
                    name = "&8" + n.name;
                    lore.add("&8Bloqueado. Requiere: &7" + missing);
                } else if (deficit) {
                    name = "&6" + n.name;
                    lore.add("&6No podes gastar: tenes puntos en deficit.");
                } else if (available < n.cost) {
                    name = "&6" + n.name;
                    lore.add("&6Te faltan " + (n.cost - available) + " punto(s)");
                } else {
                    name = "&e" + n.name;
                    lore.add("&eClick para desbloquear");
                }
            }
            inv.setItem(slot, make(n.icon, name, lore, glow));
            h.slotNode.put(slot, n.id);
        }

        // fila de controles
        inv.setItem(SLOT_BACK, make(Material.ARROW, "&fVolver", List.of("&7Volver a la lista de oficios"), false));
        List<String> info = new ArrayList<>();
        info.add("&7Nivel del trabajo: &f" + level);
        info.add("&7Puntos totales: &f" + total);
        info.add("&7Gastados: &f" + spent);
        info.add(available >= 0 ? "&7Libres: &a" + available : "&7Libres: &c" + available + " &7(deficit)");
        info.add(" ");
        info.add("&8Cada nivel del trabajo da puntos.");
        inv.setItem(SLOT_INFO, make(Material.EXPERIENCE_BOTTLE, "&bPuntos de " + t.display, info, false));
        inv.setItem(SLOT_RESET, make(Material.BARRIER, "&cReiniciar arbol",
                List.of("&7Devuelve todos los puntos.", "&7Click dos veces para confirmar.",
                        "&7Espera entre reinicios: &f" + plugin.resetCooldownHours() + " h"), false));
    }

    private String missingRequirements(Tree t, Tree.Node n, List<String> unlockedIds) {
        List<String> names = new ArrayList<>();
        for (String r : n.requires) {
            if (!unlockedIds.contains(r)) {
                Tree.Node req = t.nodes.get(r);
                names.add(req == null ? r : req.name);
            }
        }
        return String.join(", ", names).replaceAll("&[0-9a-fk-or]", "");
    }

    // ------------------------------------------------------------------
    // Clicks
    // ------------------------------------------------------------------

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Holder) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof Holder)) {
            return;
        }
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player)) {
            return;
        }
        if (e.getClickedInventory() == null || e.getClickedInventory() != top) {
            return;
        }
        Player p = (Player) e.getWhoClicked();
        Holder h = (Holder) top.getHolder();
        int slot = e.getSlot();

        if (h.kind.equals("main")) {
            String treeId = h.slotTree.get(slot);
            if (treeId != null) {
                Tree t = plugin.tree(treeId);
                if (t != null) {
                    click(p);
                    final Tree target = t;
                    plugin.getServer().getScheduler().runTask(plugin, () -> openTree(p, target));
                }
            }
            return;
        }

        Tree t = plugin.tree(h.treeId);
        if (t == null) {
            p.closeInventory();
            return;
        }

        if (slot == SLOT_BACK) {
            click(p);
            plugin.sync(p);
            plugin.getServer().getScheduler().runTask(plugin, () -> openMain(p));
            return;
        }
        if (slot == SLOT_RESET) {
            handleReset(p, t, h, top);
            return;
        }
        String nodeId = h.slotNode.get(slot);
        if (nodeId != null) {
            handleUnlock(p, t, nodeId, h, top);
        }
    }

    private void handleUnlock(Player p, Tree t, String nodeId, Holder h, Inventory inv) {
        Tree.Node n = t.nodes.get(nodeId);
        if (n == null) {
            return;
        }
        PlayerData d = plugin.data(p);
        List<String> list = d.unlocked.computeIfAbsent(t.id, k -> new ArrayList<>());
        if (list.contains(n.id)) {
            return;
        }
        int total = plugin.totalPoints(plugin.level(p, t));
        int available = total - plugin.spent(d, t);
        if (available < 0) {
            plugin.msg(p, "&cTenes puntos en deficit en este oficio. Recupera nivel antes de gastar mas.");
            return;
        }
        for (String r : n.requires) {
            if (!list.contains(r)) {
                plugin.msg(p, "&cTodavia no cumplis los requisitos de este talento.");
                return;
            }
        }
        if (available < n.cost) {
            plugin.msg(p, "&cTe faltan " + (n.cost - available) + " punto(s).");
            return;
        }
        list.add(n.id);
        d.save(plugin.dataDir());
        plugin.sync(p);
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.4f);
        plugin.msg(p, "&aDesbloqueaste &f" + n.name.replaceAll("&[0-9a-fk-or]", "") + "&a.");
        render(p, t, h, inv);
    }

    private void handleReset(Player p, Tree t, Holder h, Inventory inv) {
        PlayerData d = plugin.data(p);
        long now = System.currentTimeMillis();

        long last = d.lastReset.getOrDefault(t.id, 0L);
        long cooldown = plugin.resetCooldownHours() * 3_600_000L;
        if (last > 0 && now - last < cooldown) {
            long left = (cooldown - (now - last)) / 60_000L;
            plugin.msg(p, "&cPodes reiniciar este arbol de nuevo en " + (left / 60) + " h " + (left % 60) + " min.");
            return;
        }

        String pending = resetPending.get(p.getUniqueId());
        if (pending != null) {
            String[] parts = pending.split(":");
            if (parts.length == 2 && parts[0].equals(t.id)) {
                long when;
                try {
                    when = Long.parseLong(parts[1]);
                } catch (NumberFormatException ex) {
                    when = 0;
                }
                if (now - when <= CONFIRM_MS) {
                    resetPending.remove(p.getUniqueId());
                    plugin.resetTree(p, t);
                    p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_USE, 0.6f, 1.2f);
                    plugin.msg(p, "&aArbol reiniciado. Recuperaste todos tus puntos.");
                    render(p, t, h, inv);
                    return;
                }
            }
        }
        resetPending.put(p.getUniqueId(), t.id + ":" + now);
        plugin.msg(p, "&eHace click otra vez en 10 segundos para confirmar el reinicio.");
    }

    private void click(Player p) {
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.0f);
    }
}
