package com.surrealsmp.skills;

import org.bukkit.Material;

import java.util.Locale;

public enum SkillType {
    MINING("Mining", Material.DIAMOND_PICKAXE, "xp.mining"),
    FORAGING("Foraging", Material.IRON_AXE, "xp.foraging"),
    FARMING("Farming", Material.WHEAT, "xp.farming"),
    FISHING("Fishing", Material.FISHING_ROD, "xp.fishing"),
    EXCAVATION("Excavation", Material.IRON_SHOVEL, "xp.excavation"),
    ENCHANTING("Enchanting", Material.ENCHANTING_TABLE, "xp.enchanting-per-level");

    private final String displayName;
    private final Material icon;
    private final String configPath;

    SkillType(String displayName, Material icon, String configPath) {
        this.displayName = displayName;
        this.icon = icon;
        this.configPath = configPath;
    }

    public String displayName() {
        return displayName;
    }

    public Material icon() {
        return icon;
    }

    public String configPath() {
        return configPath;
    }

    public static SkillType fromInput(String input) {
        if (input == null) return null;
        String normalized = input.replace("_", "").replace("-", "").replace(" ", "")
                .toLowerCase(Locale.ROOT);
        for (SkillType skill : values()) {
            if (skill.name().replace("_", "").toLowerCase(Locale.ROOT).equals(normalized)
                    || skill.displayName.replace(" ", "").toLowerCase(Locale.ROOT).equals(normalized)) {
                return skill;
            }
        }
        return null;
    }
}
