package com.huerfanitos.jobsskees;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Un arbol de talentos: corresponde a un trabajo de Jobs. Se carga desde trees/<id>.yml. */
public final class Tree {

    public final String id;            // nombre del archivo, sin .yml
    public final String jobName;       // nombre del trabajo en Jobs (para leer el nivel)
    public final String display;       // nombre visible (con codigos &)
    public final Material icon;
    public final List<String> description;
    public final Map<String, Node> nodes = new LinkedHashMap<>();

    public Tree(String id, String jobName, String display, Material icon, List<String> description) {
        this.id = id;
        this.jobName = jobName;
        this.display = display;
        this.icon = icon;
        this.description = description;
    }

    /** Un nodo (talento) del arbol. */
    public static final class Node {
        public final String id;
        public final String name;
        public final Material icon;
        public final int row;          // 0..4
        public final int col;          // 0..8
        public final int cost;
        public final List<String> requires;
        public final List<String> description;
        public final List<Effect> effects = new ArrayList<>();
        public final List<String> applyCmds = new ArrayList<>();
        public final List<String> removeCmds = new ArrayList<>();

        public Node(String id, String name, Material icon, int row, int col, int cost,
                    List<String> requires, List<String> description) {
            this.id = id;
            this.name = name;
            this.icon = icon;
            this.row = row;
            this.col = col;
            this.cost = cost;
            this.requires = requires;
            this.description = description;
        }
    }

    /** Un efecto de un nodo. Los campos que se usan dependen del tipo. */
    public static final class Effect {
        public final String type;                          // drop_bonus | durability_save | attribute | commands
        public double chance;                              // drop_bonus, durability_save (0.05 = 5 %)
        public String attribute = "";                      // attribute: ej. minecraft:block_break_speed
        public double amount;                              // attribute
        public String operation = "add_number";            // attribute: add_number | add_scalar | multiply_scalar_1
        public final Set<Material> blocks = new HashSet<>();   // drop_bonus: bloques que cuentan
        public final List<String> items = new ArrayList<>();   // patrones de nombre de objeto (igual o sufijo), en mayusculas

        public Effect(String type) {
            this.type = type;
        }

        public boolean matchesBlock(Material m) {
            return blocks.contains(m);
        }

        public boolean matchesItem(Material m) {
            if (items.isEmpty()) {
                return true;
            }
            String n = m.name();
            for (String p : items) {
                if (n.equals(p) || n.endsWith(p)) {
                    return true;
                }
            }
            return false;
        }
    }
}
