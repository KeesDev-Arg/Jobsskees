package com.huerfanitos.jobsskees;

import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JobsSkees: arbol de talentos por trabajo. Cada nivel de un trabajo de Jobs da puntos
 * (por defecto 1) que se gastan en un arbol propio de ese trabajo.
 */
public final class JobsSkeesPlugin extends JavaPlugin implements Listener {

    /** Efectos activos de un jugador, listos para consultar en los eventos de juego. */
    public static final class Active {
        public final List<Tree.Effect> drops = new ArrayList<>();
        public final List<Tree.Effect> durability = new ArrayList<>();
    }

    private final Map<String, Tree> trees = new LinkedHashMap<>();
    private final Map<String, Tree.Node> nodeIndex = new HashMap<>();
    private final Map<UUID, PlayerData> cache = new ConcurrentHashMap<>();
    private final Map<UUID, Active> activeMap = new ConcurrentHashMap<>();
    private final Map<UUID, String> signatures = new ConcurrentHashMap<>();
    private final Set<String> warnedAttributes = new HashSet<>();
    private final Set<String> warnedLevels = new HashSet<>();

    private Menus menus;
    private BukkitTask task;
    private String namespace;
    private File dataDir;

    private boolean enabledEffects = true;
    private int pointsPerLevel = 1;
    private String levelPlaceholder = "%jobsr_user_jlevel_{job}%";
    private int intervalSeconds = 30;
    private int resetCooldownHours = 24;
    private String prefix = "&8[&bJobsSkees&8] &7";

    @Override
    public void onEnable() {
        saveDefaultConfig();
        namespace = new NamespacedKey(this, "x").getNamespace();
        dataDir = new File(getDataFolder(), "data");

        loadAll();

        menus = new Menus(this);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(menus, this);
        getServer().getPluginManager().registerEvents(new Effects(this), this);

        startTask();
        getLogger().info("JobsSkees activo. Arboles cargados: " + trees.size() + ".");
    }

    @Override
    public void onDisable() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (PlayerData d : cache.values()) {
            d.save(dataDir);
        }
    }

    // ------------------------------------------------------------------
    // Carga de configuracion y arboles
    // ------------------------------------------------------------------

    public void loadAll() {
        reloadConfig();
        enabledEffects = getConfig().getBoolean("enabled", true);
        pointsPerLevel = Math.max(1, getConfig().getInt("points-per-level", 1));
        levelPlaceholder = getConfig().getString("level-placeholder", "%jobsr_user_jlevel_{job}%");
        intervalSeconds = Math.max(5, getConfig().getInt("check-interval-seconds", 30));
        resetCooldownHours = Math.max(0, getConfig().getInt("reset-cooldown-hours", 24));
        prefix = getConfig().getString("prefix", "&8[&bJobsSkees&8] &7");
        loadTrees();
    }

    private void loadTrees() {
        trees.clear();
        nodeIndex.clear();

        File dir = new File(getDataFolder(), "trees");
        if (!dir.exists()) {
            dir.mkdirs();
            saveResource("trees/miner.yml", false);
        }
        File[] files = dir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (files == null || files.length == 0) {
            getLogger().warning("No hay archivos en plugins/JobsSkees/trees/. No hay arboles que mostrar.");
            return;
        }
        java.util.Arrays.sort(files);

        for (File f : files) {
            try {
                Tree t = parseTree(f);
                if (t != null) {
                    trees.put(t.id, t);
                    for (Tree.Node n : t.nodes.values()) {
                        nodeIndex.put(t.id + ":" + n.id, n);
                    }
                }
            } catch (Exception ex) {
                getLogger().warning("Error leyendo " + f.getName() + ": " + ex.getMessage());
            }
        }
    }

    private Tree parseTree(File f) {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        String id = f.getName().substring(0, f.getName().length() - 4).toLowerCase(Locale.ROOT);
        String job = y.getString("job", id);
        String display = y.getString("display", job);
        Material icon = material(y.getString("icon", "BOOK"), Material.BOOK);
        Tree tree = new Tree(id, job, display, icon, y.getStringList("description"));

        Map<String, List<String>> groups = new HashMap<>();
        ConfigurationSection gs = y.getConfigurationSection("groups");
        if (gs != null) {
            for (String k : gs.getKeys(false)) {
                groups.put(k.toLowerCase(Locale.ROOT), gs.getStringList(k));
            }
        }

        ConfigurationSection ns = y.getConfigurationSection("nodes");
        if (ns == null) {
            getLogger().warning(f.getName() + ": no tiene la seccion 'nodes'.");
            return tree;
        }

        Set<Integer> usedSlots = new HashSet<>();
        for (String nid : ns.getKeys(false)) {
            ConfigurationSection n = ns.getConfigurationSection(nid);
            if (n == null) {
                continue;
            }
            int row = n.getInt("row", 0);
            int col = n.getInt("col", 0);
            if (row < 0 || row > 4 || col < 0 || col > 8) {
                getLogger().warning(f.getName() + ": el nodo '" + nid + "' esta fuera de la grilla (fila 0-4, columna 0-8). Se omite.");
                continue;
            }
            if (!usedSlots.add(row * 9 + col)) {
                getLogger().warning(f.getName() + ": el nodo '" + nid + "' comparte posicion con otro nodo. Se omite.");
                continue;
            }
            Tree.Node node = new Tree.Node(
                    nid.toLowerCase(Locale.ROOT),
                    n.getString("name", nid),
                    material(n.getString("icon", "PAPER"), Material.PAPER),
                    row, col,
                    Math.max(1, n.getInt("cost", 1)),
                    lowerList(n.getStringList("requires")),
                    n.getStringList("description"));

            for (Map<?, ?> m : n.getMapList("effects")) {
                Tree.Effect e = parseEffect(m, groups, f.getName() + ":" + nid);
                if (e == null) {
                    continue;
                }
                if (e.type.equals("commands")) {
                    node.applyCmds.addAll(strList(m.get("apply")));
                    node.removeCmds.addAll(strList(m.get("remove")));
                }
                node.effects.add(e);
            }
            tree.nodes.put(node.id, node);
        }

        for (Tree.Node n : tree.nodes.values()) {
            for (String r : n.requires) {
                if (!tree.nodes.containsKey(r)) {
                    getLogger().warning(f.getName() + ": el nodo '" + n.id + "' requiere '" + r + "', que no existe.");
                }
            }
        }
        return tree;
    }

    private Tree.Effect parseEffect(Map<?, ?> m, Map<String, List<String>> groups, String where) {
        Object typeObj = m.get("type");
        if (typeObj == null) {
            getLogger().warning(where + ": un efecto no tiene 'type'. Se omite.");
            return null;
        }
        String type = String.valueOf(typeObj).toLowerCase(Locale.ROOT);
        if (!type.equals("drop_bonus") && !type.equals("durability_save")
                && !type.equals("attribute") && !type.equals("commands")) {
            getLogger().warning(where + ": tipo de efecto desconocido '" + type + "'. Se omite.");
            return null;
        }
        Tree.Effect e = new Tree.Effect(type);
        e.chance = num(m.get("chance"));
        e.amount = num(m.get("amount"));
        if (m.get("attribute") != null) {
            e.attribute = String.valueOf(m.get("attribute"));
        }
        if (m.get("operation") != null) {
            e.operation = String.valueOf(m.get("operation")).toLowerCase(Locale.ROOT);
        }
        for (String s : strList(m.get("blocks"))) {
            if (s.startsWith("@")) {
                List<String> g = groups.get(s.substring(1).toLowerCase(Locale.ROOT));
                if (g == null) {
                    getLogger().warning(where + ": el grupo '" + s + "' no existe.");
                } else {
                    for (String x : g) {
                        addMaterial(e.blocks, x, where);
                    }
                }
            } else {
                addMaterial(e.blocks, s, where);
            }
        }
        for (String s : strList(m.get("items"))) {
            e.items.add(s.toUpperCase(Locale.ROOT));
        }
        if (type.equals("drop_bonus") && e.blocks.isEmpty()) {
            getLogger().warning(where + ": 'drop_bonus' necesita 'blocks'. Se omite.");
            return null;
        }
        if (type.equals("attribute") && e.attribute.isEmpty()) {
            getLogger().warning(where + ": 'attribute' necesita el campo 'attribute'. Se omite.");
            return null;
        }
        return e;
    }

    private void addMaterial(Set<Material> set, String name, String where) {
        Material m = Material.matchMaterial(name.trim());
        if (m == null) {
            getLogger().warning(where + ": material desconocido '" + name + "'.");
        } else {
            set.add(m);
        }
    }

    private Material material(String name, Material fallback) {
        Material m = name == null ? null : Material.matchMaterial(name.trim());
        return m == null ? fallback : m;
    }

    private static double num(Object o) {
        if (o instanceof Number) {
            return ((Number) o).doubleValue();
        }
        if (o != null) {
            try {
                return Double.parseDouble(String.valueOf(o));
            } catch (NumberFormatException ex) {
                return 0;
            }
        }
        return 0;
    }

    private static List<String> strList(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof Collection) {
            for (Object x : (Collection<?>) o) {
                out.add(String.valueOf(x));
            }
        }
        return out;
    }

    private static List<String> lowerList(List<String> in) {
        List<String> out = new ArrayList<>();
        for (String s : in) {
            out.add(s.toLowerCase(Locale.ROOT));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Acceso a datos y calculo de puntos
    // ------------------------------------------------------------------

    public Collection<Tree> trees() {
        return trees.values();
    }

    public Tree tree(String id) {
        return trees.get(id);
    }

    public File dataDir() {
        return dataDir;
    }

    public int resetCooldownHours() {
        return resetCooldownHours;
    }

    public PlayerData data(Player p) {
        return cache.computeIfAbsent(p.getUniqueId(), id -> PlayerData.load(dataDir, id));
    }

    public Active active(Player p) {
        return activeMap.get(p.getUniqueId());
    }

    public String rawLevel(Player p, Tree t) {
        return PlaceholderAPI.setPlaceholders(p, levelPlaceholder.replace("{job}", t.jobName));
    }

    public int level(Player p, Tree t) {
        String raw = rawLevel(p, t);
        try {
            return Math.max(0, Integer.parseInt(raw.trim()));
        } catch (NumberFormatException ex) {
            if (warnedLevels.add(t.id)) {
                getLogger().warning("El placeholder de nivel devolvio '" + raw + "' para el trabajo " + t.jobName
                        + " (no es un numero). Se toma como nivel 0. Revisa 'level-placeholder' en config.yml. "
                        + "Este aviso se muestra una sola vez por trabajo.");
            }
            return 0;
        }
    }

    public int totalPoints(int level) {
        return level * pointsPerLevel;
    }

    /** Puntos gastados en TODOS los nodos desbloqueados del arbol (activos o suspendidos). */
    public int spent(PlayerData d, Tree t) {
        int sum = 0;
        for (String id : d.unlocked.getOrDefault(t.id, new ArrayList<>())) {
            Tree.Node n = t.nodes.get(id);
            if (n != null) {
                sum += n.cost;
            }
        }
        return sum;
    }

    /**
     * Nodos ACTIVOS: se recorren en el orden en que se desbloquearon y se detiene en el primero
     * que ya no entra en los puntos disponibles. Asi, si el jugador pierde niveles, se suspenden
     * primero los nodos mas recientes.
     */
    public List<Tree.Node> activeNodes(PlayerData d, Tree t, int totalPoints) {
        List<Tree.Node> out = new ArrayList<>();
        int used = 0;
        for (String id : d.unlocked.getOrDefault(t.id, new ArrayList<>())) {
            Tree.Node n = t.nodes.get(id);
            if (n == null) {
                continue;
            }
            if (used + n.cost > totalPoints) {
                break;
            }
            used += n.cost;
            out.add(n);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Sincronizacion de efectos
    // ------------------------------------------------------------------

    private void startTask() {
        if (task != null) {
            task.cancel();
        }
        task = getServer().getScheduler().runTaskTimer(this, this::syncAll, 100L, intervalSeconds * 20L);
    }

    public void syncAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            sync(p);
        }
    }

    public void sync(Player p) {
        PlayerData d = data(p);
        Active act = new Active();
        Set<String> desiredCmd = new LinkedHashSet<>();
        List<String> attrKeys = new ArrayList<>();
        List<Tree.Effect> attrEffects = new ArrayList<>();
        StringBuilder sig = new StringBuilder(enabledEffects ? "on" : "off");

        if (enabledEffects) {
            for (Tree t : trees.values()) {
                int total = totalPoints(level(p, t));
                for (Tree.Node n : activeNodes(d, t, total)) {
                    sig.append('|').append(t.id).append(':').append(n.id);
                    int idx = 0;
                    for (Tree.Effect e : n.effects) {
                        switch (e.type) {
                            case "drop_bonus":
                                act.drops.add(e);
                                break;
                            case "durability_save":
                                act.durability.add(e);
                                break;
                            case "attribute":
                                attrKeys.add(cleanKey("n_" + t.id + "_" + n.id + "_" + idx));
                                attrEffects.add(e);
                                break;
                            case "commands":
                                desiredCmd.add(t.id + ":" + n.id);
                                break;
                            default:
                                break;
                        }
                        idx++;
                    }
                }
            }
        }
        activeMap.put(p.getUniqueId(), act);

        String signature = sig.toString();
        if (signature.equals(signatures.get(p.getUniqueId()))) {
            return; // nada cambio desde la ultima vez
        }
        signatures.put(p.getUniqueId(), signature);

        // 1) atributos: quitar los nuestros y volver a poner los activos
        removeOurModifiers(p);
        for (int i = 0; i < attrEffects.size(); i++) {
            applyAttribute(p, attrKeys.get(i), attrEffects.get(i));
        }

        // 2) comandos (permisos, etc.): ejecutar los de los nodos nuevos y revertir los de los quitados
        Set<String> previous = new LinkedHashSet<>(d.applied);
        boolean changed = false;
        for (String key : desiredCmd) {
            if (!previous.contains(key)) {
                Tree.Node n = nodeIndex.get(key);
                if (n != null) {
                    runCommands(p, n.applyCmds);
                }
                changed = true;
            }
        }
        for (String key : previous) {
            if (!desiredCmd.contains(key)) {
                Tree.Node n = nodeIndex.get(key);
                if (n != null) {
                    runCommands(p, n.removeCmds);
                }
                changed = true;
            }
        }
        if (changed) {
            d.applied.clear();
            d.applied.addAll(desiredCmd);
            d.save(dataDir);
        }
    }

    private static String cleanKey(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
    }

    private void removeOurModifiers(Player p) {
        for (Attribute a : Registry.ATTRIBUTE) {
            AttributeInstance inst = p.getAttribute(a);
            if (inst == null) {
                continue;
            }
            for (AttributeModifier m : new ArrayList<>(inst.getModifiers())) {
                if (m.getKey().getNamespace().equals(namespace)) {
                    inst.removeModifier(m);
                }
            }
        }
    }

    private void applyAttribute(Player p, String key, Tree.Effect e) {
        NamespacedKey attrKey = NamespacedKey.fromString(e.attribute.toLowerCase(Locale.ROOT));
        Attribute attr = attrKey == null ? null : Registry.ATTRIBUTE.get(attrKey);
        if (attr == null) {
            if (warnedAttributes.add(e.attribute)) {
                getLogger().warning("El atributo '" + e.attribute + "' no existe en esta version del servidor. Se omite.");
            }
            return;
        }
        AttributeInstance inst = p.getAttribute(attr);
        if (inst == null) {
            return;
        }
        AttributeModifier.Operation op;
        switch (e.operation) {
            case "add_scalar":
                op = AttributeModifier.Operation.ADD_SCALAR;
                break;
            case "multiply_scalar_1":
                op = AttributeModifier.Operation.MULTIPLY_SCALAR_1;
                break;
            default:
                op = AttributeModifier.Operation.ADD_NUMBER;
                break;
        }
        inst.addModifier(new AttributeModifier(new NamespacedKey(this, key), e.amount, op, EquipmentSlotGroup.ANY));
    }

    private void runCommands(Player p, List<String> cmds) {
        for (String c : cmds) {
            String cmd = c.replace("{player}", p.getName()).replace("{uuid}", p.getUniqueId().toString());
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
        }
    }

    /** Borra todos los talentos de un arbol de un jugador y revierte sus efectos. */
    public void resetTree(Player p, Tree t) {
        PlayerData d = data(p);
        d.unlocked.remove(t.id);
        d.lastReset.put(t.id, System.currentTimeMillis());
        d.save(dataDir);
        signatures.remove(p.getUniqueId());
        sync(p);
    }

    // ------------------------------------------------------------------
    // Mensajes
    // ------------------------------------------------------------------

    public void msg(CommandSender to, String text) {
        to.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(prefix + text));
    }

    // ------------------------------------------------------------------
    // Eventos
    // ------------------------------------------------------------------

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        final Player p = event.getPlayer();
        getServer().getScheduler().runTaskLater(this, () -> {
            if (p.isOnline()) {
                signatures.remove(p.getUniqueId());
                sync(p);
            }
        }, 60L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        PlayerData d = cache.remove(id);
        if (d != null) {
            d.save(dataDir);
        }
        activeMap.remove(id);
        signatures.remove(id);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        final Player p = event.getPlayer();
        signatures.remove(p.getUniqueId());
        getServer().getScheduler().runTaskLater(this, () -> {
            if (p.isOnline()) {
                sync(p);
            }
        }, 5L);
    }

    // ------------------------------------------------------------------
    // Comandos: /oficios  |  /oficios reload  |  /oficios info [jugador]  |  /oficios reset <jugador>
    // ------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player)) {
                msg(sender, "Este comando es para jugadores. Usa: /" + label + " reload | info <jugador> | reset <jugador>");
                return true;
            }
            Player p = (Player) sender;
            if (!p.hasPermission("jobsskees.use")) {
                msg(p, "&cNo tenes permiso.");
                return true;
            }
            sync(p);
            menus.openMain(p);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        if (!sender.hasPermission("jobsskees.admin")) {
            msg(sender, "&cNo tenes permiso.");
            return true;
        }

        if (sub.equals("reload")) {
            loadAll();
            startTask();
            signatures.clear();
            syncAll();
            msg(sender, "Recargado. Arboles: " + trees.size() + ".");
            return true;
        }

        if (sub.equals("info")) {
            Player target = null;
            if (args.length >= 2) {
                target = Bukkit.getPlayerExact(args[1]);
            } else if (sender instanceof Player) {
                target = (Player) sender;
            }
            if (target == null) {
                msg(sender, "Jugador no encontrado o desconectado.");
                return true;
            }
            PlayerData d = data(target);
            msg(sender, "Info de " + target.getName() + " (efectos activados: " + enabledEffects + ")");
            for (Tree t : trees.values()) {
                String raw = rawLevel(target, t);
                int lv = level(target, t);
                int total = totalPoints(lv);
                int spent = spent(d, t);
                int active = activeNodes(d, t, total).size();
                int unlocked = d.unlocked.getOrDefault(t.id, new ArrayList<>()).size();
                msg(sender, "&f" + t.id + "&7: placeholder='" + raw + "' nivel=" + lv + " puntos=" + total
                        + " gastados=" + spent + " nodos=" + unlocked + " (activos " + active + ")");
            }
            return true;
        }

        if (sub.equals("reset")) {
            if (args.length < 2) {
                msg(sender, "Uso: /" + label + " reset <jugador>");
                return true;
            }
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                msg(sender, "Jugador no encontrado o desconectado.");
                return true;
            }
            PlayerData d = data(target);
            d.unlocked.clear();
            d.save(dataDir);
            signatures.remove(target.getUniqueId());
            sync(target);
            msg(sender, "Se borraron todos los talentos de " + target.getName() + ".");
            return true;
        }

        msg(sender, "Uso: /" + label + " reload | info [jugador] | reset <jugador>");
        return true;
    }
}
