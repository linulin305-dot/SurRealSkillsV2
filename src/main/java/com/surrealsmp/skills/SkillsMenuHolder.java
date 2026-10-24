package com.surrealsmp.skills;

import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public final class SkillsMenuHolder implements InventoryHolder {
    private final Inventory inventory;

    public SkillsMenuHolder() {
        this.inventory = Bukkit.createInventory(this, 27, "§5§lSurRealSkills");
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
