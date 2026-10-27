package com.huerfanitos.jobsskees;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Datos de un jugador. Se guardan en plugins/JobsSkees/data/<uuid>.yml */
public final class PlayerData {

    public final UUID uuid;
    /** arbol -> ids de nodos desbloqueados, EN ORDEN de desbloqueo. */
    public final Map<String, List<String>> unlocked = new HashMap<>();
    /** arbol -> momento (ms) del ultimo reinicio. */
    public final Map<String, Long> lastReset = new HashMap<>();
    /** "arbol:nodo" de los nodos cuyos comandos ya se ejecutaron (para poder revertirlos). */
    public final Set<String> applied = new LinkedHashSet<>();

    public PlayerData(UUID uuid) {
        this.uuid = uuid;
    }

    public static PlayerData load(File dir, UUID uuid) {
        PlayerData d = new PlayerData(uuid);
        File f = new File(dir, uuid + ".yml");
        if (!f.exists()) {
            return d;
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);

        ConfigurationSection us = y.getConfigurationSection("unlocked");
        if (us != null) {
            for (String k : us.getKeys(false)) {
                d.unlocked.put(k, new ArrayList<>(us.getStringList(k)));
            }
        }
        ConfigurationSection rs = y.getConfigurationSection("reset");
        if (rs != null) {
            for (String k : rs.getKeys(false)) {
                d.lastReset.put(k, rs.getLong(k));
            }
        }
        d.applied.addAll(y.getStringList("applied"));
        return d;
    }

    public void save(File dir) {
        if (!dir.exists()) {
            dir.mkdirs();
        }
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<String, List<String>> e : unlocked.entrySet()) {
            y.set("unlocked." + e.getKey(), e.getValue());
        }
        for (Map.Entry<String, Long> e : lastReset.entrySet()) {
            y.set("reset." + e.getKey(), e.getValue());
        }
        y.set("applied", new ArrayList<>(applied));
        try {
            y.save(new File(dir, uuid + ".yml"));
        } catch (IOException ex) {
            Bukkit.getLogger().warning("[JobsSkees] No se pudo guardar los datos de " + uuid + ": " + ex.getMessage());
        }
    }
}
