package com.surrealsmp.skills;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class SurRealSkills extends JavaPlugin implements Listener {
    private static final long DEFAULT_XP_CAP = 1_000_000_000_000L;
    private final Map<UUID, EnumMap<SkillType, SkillProgress>> playerProgress = new HashMap<>();
    private final Map<UUID, String> playerNames = new HashMap<>();
    private final Set<String> placedBlocks = new HashSet<>();
    private final Map<UUID, Integer> oakLogProgress = new HashMap<>();
    private final Map<UUID, BossBar> progressBars = new HashMap<>();
    private final Map<UUID, Long> progressDisplayTokens = new HashMap<>();

    private File dataFile;
    private YamlConfiguration data;
    private long lastSaveWarningMillis;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadData();
        Bukkit.getPluginManager().registerEvents(this, this);

        if (getCommand("skills") == null) {
            getLogger().severe("Command 'skills' is missing from plugin.yml. Disabling plugin.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        // Save on the main server thread: Bukkit YAML configuration objects are not thread-safe.
        new BukkitRunnable() {
            @Override
            public void run() {
                saveData();
            }
        }.runTaskTimer(this, 20L * 60L * 5L, 20L * 60L * 5L);

        getLogger().info("SurRealSkills enabled. Author: surrealsmpofficial");
    }

    @Override
    public void onDisable() {
        saveData();
        for (BossBar bar : progressBars.values()) {
            bar.removeAll();
        }
        progressBars.clear();
        progressDisplayTokens.clear();
        getLogger().info("SurRealSkills disabled.");
    }

    private String color(String input) {
        return ChatColor.translateAlternateColorCodes('&', input == null ? "" : input);
    }

    private String prefix() {
        return color(getConfig().getString("messages.prefix", "&5&lSurRealSkills &8» "));
    }

    private void send(CommandSender sender, String messageKey) {
        sender.sendMessage(prefix() + color(getConfig().getString("messages." + messageKey, "&cMissing message: " + messageKey)));
    }

    private EnumMap<SkillType, SkillProgress> getPlayerSkills(UUID uuid) {
        return playerProgress.computeIfAbsent(uuid, ignored -> {
            EnumMap<SkillType, SkillProgress> skills = new EnumMap<>(SkillType.class);
            for (SkillType type : SkillType.values()) {
                skills.put(type, new SkillProgress(0L));
            }
            return skills;
        });
    }

    private SkillProgress getProgress(UUID uuid, SkillType type) {
        return getPlayerSkills(uuid).computeIfAbsent(type, ignored -> new SkillProgress(0L));
    }

    private long xpCap() {
        return Math.max(1L, getConfig().getLong("progression.max-total-xp-per-skill", DEFAULT_XP_CAP));
    }

    private long xpToNextLevel(long totalXp) {
        int level = levelFor(totalXp);
        long base = Math.max(1L, getConfig().getLong("progression.base-xp", 100L));
        long increase = Math.max(0L, getConfig().getLong("progression.xp-per-level-increase", 50L));
        long required = base + (long) (level - 1) * increase;
        long intoLevel = xpIntoLevel(totalXp);
        return Math.max(0L, required - intoLevel);
    }

    private long xpIntoLevel(long totalXp) {
        long remaining = Math.max(0L, totalXp);
        long base = Math.max(1L, getConfig().getLong("progression.base-xp", 100L));
        long increase = Math.max(0L, getConfig().getLong("progression.xp-per-level-increase", 50L));

        for (int level = 1; level < 100_000; level++) {
            long needed = base + (long) (level - 1) * increase;
            if (remaining < needed) return remaining;
            remaining -= needed;
        }
        return remaining;
    }

    private int levelFor(long totalXp) {
        long remaining = Math.max(0L, totalXp);
        long base = Math.max(1L, getConfig().getLong("progression.base-xp", 100L));
        long increase = Math.max(0L, getConfig().getLong("progression.xp-per-level-increase", 50L));
        int level = 1;

        while (level < 100_000) {
            long needed = base + (long) (level - 1) * increase;
            if (remaining < needed) break;
            remaining -= needed;
            level++;
        }
        return level;
    }

    private int configuredXp(SkillType skill) {
        return Math.max(0, getConfig().getInt(skill.configPath(), defaultXp(skill)));
    }

    private int defaultXp(SkillType skill) {
        return switch (skill) {
            case MINING, FARMING, EXCAVATION -> 2;
            case FORAGING -> 3;
            case FISHING -> 10;
            case ENCHANTING -> 5;
        };
    }

    private boolean canEarn(Player player) {
        return player.getGameMode() != GameMode.CREATIVE
                && player.getGameMode() != GameMode.SPECTATOR;
    }

    private void awardXp(Player player, SkillType skill, long requestedAmount) {
        if (requestedAmount <= 0L) return;

        SkillProgress progress = getProgress(player.getUniqueId(), skill);
        long before = progress.totalXp();
        progress.addXp(Math.min(requestedAmount, 10_000L), xpCap());
        long after = progress.totalXp();
        long actualAmount = after - before;
        if (actualAmount <= 0L) return;

        playerNames.put(player.getUniqueId(), player.getName());
        // Skill XP and level progression are shown through the boss bar/action bar,
        // not chat. Keep progression silent here so mining does not spam chat.
        // The caller refreshes the on-screen skill progress after awarding XP.
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        placedBlocks.add(blockKey(event.getBlockPlaced()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (!canEarn(player)) return;

        Block block = event.getBlock();
        String key = blockKey(block);
        boolean wasPlaced = placedBlocks.remove(key);
        if (wasPlaced) return;

        Material material = block.getType();
        if (isLog(material)) {
            if (material == Material.OAK_LOG) {
                handleOakLogProgress(player);
            } else {
                awardXp(player, SkillType.FORAGING, configuredXp(SkillType.FORAGING));
                showSkillProgress(player, SkillType.FORAGING);
            }
            return;
        }

        if (isMatureCrop(block)) {
            awardXp(player, SkillType.FARMING, configuredXp(SkillType.FARMING));
            showSkillProgress(player, SkillType.FARMING);
            return;
        }

        if (isExcavationBlock(material)) {
            awardXp(player, SkillType.EXCAVATION, configuredXp(SkillType.EXCAVATION));
            showSkillProgress(player, SkillType.EXCAVATION);
            return;
        }

        if (isMiningBlock(material)) {
            awardXp(player, SkillType.MINING, configuredXp(SkillType.MINING));
            showSkillProgress(player, SkillType.MINING);
        }
    }

    private void handleOakLogProgress(Player player) {
        UUID uuid = player.getUniqueId();
        int required = Math.max(1, getConfig().getInt("oak-logs.logs-per-level", 20));
        int count = oakLogProgress.getOrDefault(uuid, 0) + 1;

        if (count >= required) {
            oakLogProgress.put(uuid, 0);
            SkillProgress progress = getProgress(uuid, SkillType.FORAGING);
            long xpNeeded = xpToNextLevel(progress.totalXp());
            if (xpNeeded > 0L) {
                awardXp(player, SkillType.FORAGING, xpNeeded);
            }

            int rewardLevels = Math.max(0, getConfig().getInt("oak-logs.minecraft-xp-levels-reward", 5));
            if (rewardLevels > 0) {
                player.setLevel(Math.max(0, player.getLevel()) + rewardLevels);
            }

            String title = getConfig().getString("progress-display.level-up-title",
                    "&6&lLEVEL UP! &dForaging &7| &f+{reward} XP levels")
                    .replace("{reward}", String.valueOf(rewardLevels))
                    .replace("{level}", String.valueOf(levelFor(progress.totalXp())));
            showProgress(player, SkillType.FORAGING, required, required, title,
                    getConfig().getString("progress-display.level-up-actionbar",
                            "&6&lLEVEL UP! &dForaging &8| &f+{reward} XP levels")
                            .replace("{reward}", String.valueOf(rewardLevels))
                            .replace("{level}", String.valueOf(levelFor(progress.totalXp()))));
        } else {
            oakLogProgress.put(uuid, count);
            int foragingLevel = levelFor(getProgress(uuid, SkillType.FORAGING).totalXp());
            String title = getConfig().getString("progress-display.oak-bossbar",
                    "&d&lForaging &8| &fLevel &d{level}")
                    .replace("{level}", String.valueOf(foragingLevel))
                    .replace("{progress}", String.valueOf(count))
                    .replace("{required}", String.valueOf(required));
            String actionbar = getConfig().getString("progress-display.oak-actionbar",
                    "&dForaging &8| &fLevel &d{level}")
                    .replace("{level}", String.valueOf(foragingLevel))
                    .replace("{progress}", String.valueOf(count))
                    .replace("{required}", String.valueOf(required));
            showProgress(player, SkillType.FORAGING, count, required, title, actionbar);
        }
    }

    private void showSkillProgress(Player player, SkillType skill) {
        SkillProgress progress = getProgress(player.getUniqueId(), skill);
        long totalXp = progress.totalXp();
        int level = levelFor(totalXp);
        long base = Math.max(1L, getConfig().getLong("progression.base-xp", 100L));
        long increase = Math.max(0L, getConfig().getLong("progression.xp-per-level-increase", 50L));
        long required = base + (long) (level - 1) * increase;
        long remaining = xpToNextLevel(totalXp);
        long intoLevel = Math.max(0L, required - remaining);
        String title = getConfig().getString("progress-display.skill-bossbar",
                "&d&l{skill} &8| &fLevel &d{level}")
                .replace("{skill}", skill.displayName())
                .replace("{level}", String.valueOf(level))
                .replace("{progress}", String.valueOf(intoLevel))
                .replace("{required}", String.valueOf(required));
        String actionbar = getConfig().getString("progress-display.skill-actionbar",
                "&d{skill} &8| &fLevel &d{level}")
                .replace("{skill}", skill.displayName())
                .replace("{level}", String.valueOf(level))
                .replace("{progress}", String.valueOf(intoLevel))
                .replace("{required}", String.valueOf(required));
        showProgress(player, skill, intoLevel, required, title, actionbar);
    }

    private void showProgress(Player player, SkillType skill, long current, long required,
                              String title, String actionbar) {
        UUID uuid = player.getUniqueId();
        BossBar bar = progressBars.computeIfAbsent(uuid, ignored ->
                Bukkit.createBossBar(color(title), BarColor.PURPLE, BarStyle.SOLID));
        bar.setTitle(color(title));
        bar.setColor(parseBarColor(getConfig().getString("progress-display.bossbar-color", "PURPLE")));
        bar.setStyle(BarStyle.SOLID);
        double progress = required <= 0L ? 0.0D : Math.max(0.0D, Math.min(1.0D, (double) current / (double) required));
        bar.setProgress(progress);
        if (!bar.getPlayers().contains(player)) bar.addPlayer(player);
        if (!bar.isVisible()) bar.setVisible(true);
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(color(actionbar)));

        long token = progressDisplayTokens.getOrDefault(uuid, 0L) + 1L;
        progressDisplayTokens.put(uuid, token);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (progressDisplayTokens.getOrDefault(uuid, -1L) == token) {
                BossBar currentBar = progressBars.get(uuid);
                if (currentBar != null) currentBar.setVisible(false);
            }
        }, Math.max(1L, getConfig().getLong("progress-display.hide-after-ticks", 60L)));
    }

    private BarColor parseBarColor(String input) {
        try {
            return BarColor.valueOf(input == null ? "PURPLE" : input.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return BarColor.PURPLE;
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFishing(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH) return;
        if (!canEarn(event.getPlayer())) return;
        awardXp(event.getPlayer(), SkillType.FISHING, configuredXp(SkillType.FISHING));
    }

    @EventHandler(ignoreCancelled = true)
    public void onEnchant(EnchantItemEvent event) {
        Player player = event.getEnchanter();
        if (!canEarn(player)) return;
        int perLevel = configuredXp(SkillType.ENCHANTING);
        int cost = Math.max(1, event.getExpLevelCost());
        long reward = Math.min(100L, (long) perLevel * cost);
        awardXp(player, SkillType.ENCHANTING, reward);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        playerNames.put(event.getPlayer().getUniqueId(), event.getPlayer().getName());
        getPlayerSkills(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        progressDisplayTokens.remove(uuid);
        BossBar bar = progressBars.remove(uuid);
        if (bar != null) bar.removeAll();
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof SkillsMenuHolder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType().isAir()) return;
        String name = clicked.hasItemMeta() && clicked.getItemMeta().hasDisplayName()
                ? ChatColor.stripColor(clicked.getItemMeta().getDisplayName()) : null;
        SkillType skill = SkillType.fromInput(name);
        if (skill != null) {
            player.sendMessage(prefix() + color("&f" + skill.displayName() + " &8» &dLevel "
                    + levelFor(getProgress(player.getUniqueId(), skill).totalXp())
                    + " &8| &f" + getProgress(player.getUniqueId(), skill).totalXp()
                    + " total XP &8| &f" + xpToNextLevel(getProgress(player.getUniqueId(), skill).totalXp())
                    + " XP to next level."));
        }
    }

    private boolean isLog(Material material) {
        String name = material.name();
        return name.endsWith("_LOG") || name.endsWith("_WOOD")
                || name.equals("CRIMSON_STEM") || name.equals("WARPED_STEM")
                || name.equals("CRIMSON_HYPHAE") || name.equals("WARPED_HYPHAE");
    }

    private boolean isMatureCrop(Block block) {
        Material material = block.getType();
        String name = material.name();
        boolean crop = name.equals("WHEAT") || name.equals("CARROTS") || name.equals("POTATOES")
                || name.equals("BEETROOTS") || name.equals("NETHER_WART")
                || name.equals("COCOA") || name.equals("SWEET_BERRY_BUSH");
        if (!crop) return false;

        if (block.getBlockData() instanceof Ageable ageable) {
            return ageable.getAge() >= ageable.getMaximumAge();
        }
        // Sweet berry bushes implement Ageable on supported Bukkit versions.
        return false;
    }

    private boolean isExcavationBlock(Material material) {
        return switch (material) {
            case DIRT, GRASS_BLOCK, COARSE_DIRT, ROOTED_DIRT, PODZOL, MYCELIUM,
                    SAND, RED_SAND, GRAVEL, CLAY, SOUL_SAND, SOUL_SOIL,
                    MUD, PACKED_MUD, MUDDY_MANGROVE_ROOTS, SUSPICIOUS_SAND,
                    SUSPICIOUS_GRAVEL -> true;
            default -> false;
        };
    }

    private boolean isMiningBlock(Material material) {
        String name = material.name();
        if (isLog(material) || isExcavationBlock(material)) return false;
        if (name.endsWith("_ORE") || name.equals("ANCIENT_DEBRIS")
                || name.equals("NETHER_QUARTZ_ORE") || name.equals("NETHER_GOLD_ORE")) {
            return true;
        }
        return switch (material) {
            case STONE, DEEPSLATE, COBBLESTONE, COBBLED_DEEPSLATE,
                    ANDESITE, DIORITE, GRANITE, TUFF, CALCITE,
                    BLACKSTONE, BASALT, POLISHED_BASALT, NETHERRACK,
                    END_STONE, OBSIDIAN, CRYING_OBSIDIAN, PRISMARINE,
                    DARK_PRISMARINE, PRISMARINE_BRICKS, AMETHYST_BLOCK,
                    BUDDING_AMETHYST, COAL_BLOCK, RAW_IRON_BLOCK,
                    RAW_COPPER_BLOCK, RAW_GOLD_BLOCK, IRON_BLOCK,
                    COPPER_BLOCK, GOLD_BLOCK, DIAMOND_BLOCK,
                    EMERALD_BLOCK, LAPIS_BLOCK, REDSTONE_BLOCK -> true;
            default -> false;
        };
    }

    private String blockKey(Block block) {
        return block.getWorld().getUID() + ":" + block.getX() + ":" + block.getY() + ":" + block.getZ();
    }

    private void openMenu(Player player) {
        SkillsMenuHolder holder = new SkillsMenuHolder();
        Inventory inventory = holder.getInventory();

        ItemStack filler = makeItem(Material.PURPLE_STAINED_GLASS_PANE, "&8 ", List.of());
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler);
        }

        int[] slots = {10, 11, 12, 13, 14, 15};
        for (int i = 0; i < SkillType.values().length; i++) {
            SkillType skill = SkillType.values()[i];
            SkillProgress progress = getProgress(player.getUniqueId(), skill);
            int level = levelFor(progress.totalXp());
            long next = xpToNextLevel(progress.totalXp());
            List<String> lore = List.of(
                    "&7Level: &d" + level,
                    "&7Total XP: &d" + progress.totalXp(),
                    "&7XP to next level: &d" + next,
                    "",
                    "&eClick for details"
            );
            inventory.setItem(slots[i], makeItem(skill.icon(), "&d&l" + skill.displayName(), lore));
        }

        inventory.setItem(22, makeItem(Material.BOOK, "&f&lYour Progress",
                List.of("&7Skills: &d" + SkillType.values().length,
                        "&7Player: &f" + player.getName(),
                        "",
                        "&7Keep playing to level up!")));
        player.openInventory(inventory);
    }

    private ItemStack makeItem(Material material, String displayName, List<String> loreLines) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(displayName));
            List<String> lore = new ArrayList<>();
            for (String line : loreLines) lore.add(color(line));
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private void showStats(CommandSender sender, UUID uuid, String name) {
        sender.sendMessage(prefix() + color(getConfig().getString("messages.stats-header",
                "&d&l{player}'s Skills").replace("{player}", name)));
        for (SkillType skill : SkillType.values()) {
            long xp = getProgress(uuid, skill).totalXp();
            String line = getConfig().getString("messages.stats-line",
                    "&f{skill}: &dLevel {level} &8| &fTotal XP: &d{xp} &8| &fNext level: &d{needed} XP");
            sender.sendMessage(prefix() + color(line
                    .replace("{skill}", skill.displayName())
                    .replace("{level}", String.valueOf(levelFor(xp)))
                    .replace("{xp}", String.valueOf(xp))
                    .replace("{needed}", String.valueOf(xpToNextLevel(xp)))));
        }
    }

    private void showTop(CommandSender sender, SkillType skill) {
        List<Map.Entry<UUID, EnumMap<SkillType, SkillProgress>>> entries =
                new ArrayList<>(playerProgress.entrySet());
        entries.sort(Comparator.comparingLong(
                entry -> -entry.getValue().getOrDefault(skill, new SkillProgress(0L)).totalXp()));

        sender.sendMessage(prefix() + color(getConfig().getString("messages.top-header",
                "&d&lTop {skill} Players").replace("{skill}", skill.displayName())));

        int shown = 0;
        for (Map.Entry<UUID, EnumMap<SkillType, SkillProgress>> entry : entries) {
            long xp = entry.getValue().getOrDefault(skill, new SkillProgress(0L)).totalXp();
            if (xp <= 0L) continue;
            String name = playerNames.get(entry.getKey());
            if (name == null) {
                OfflinePlayer offline = Bukkit.getOfflinePlayer(entry.getKey());
                name = offline.getName() == null ? "Unknown" : offline.getName();
            }
            shown++;
            String line = getConfig().getString("messages.top-entry",
                    "&d#{rank} &f{player} &8- &dLevel {level} &7({xp} XP)");
            sender.sendMessage(prefix() + color(line
                    .replace("{rank}", String.valueOf(shown))
                    .replace("{player}", name)
                    .replace("{level}", String.valueOf(levelFor(xp)))
                    .replace("{xp}", String.valueOf(xp))));
            if (shown >= 10) break;
        }
        if (shown == 0) send(sender, "top-empty");
    }

    private void loadData() {
        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            getLogger().warning("Could not create plugin data folder.");
        }
        dataFile = new File(getDataFolder(), "data.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);

        ConfigurationSection players = data.getConfigurationSection("players");
        if (players != null) {
            for (String uuidString : players.getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(uuidString);
                    EnumMap<SkillType, SkillProgress> skills = new EnumMap<>(SkillType.class);
                    for (SkillType skill : SkillType.values()) {
                        long xp = Math.max(0L, data.getLong("players." + uuidString + ".skills." + skill.name(), 0L));
                        skills.put(skill, new SkillProgress(Math.min(xp, xpCap())));
                    }
                    playerProgress.put(uuid, skills);
                    String name = data.getString("players." + uuidString + ".name");
                    if (name != null && !name.isBlank()) playerNames.put(uuid, name);
                } catch (IllegalArgumentException exception) {
                    getLogger().warning("Skipping invalid UUID in data.yml: " + uuidString);
                }
            }
        }

        List<String> placed = data.getStringList("placed-blocks");
        placedBlocks.addAll(placed);

        ConfigurationSection oakProgress = data.getConfigurationSection("oak-log-progress");
        if (oakProgress != null) {
            for (String uuidString : oakProgress.getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(uuidString);
                    int count = Math.max(0, oakProgress.getInt(uuidString, 0));
                    oakLogProgress.put(uuid, Math.min(count,
                            Math.max(1, getConfig().getInt("oak-logs.logs-per-level", 20)) - 1));
                } catch (IllegalArgumentException exception) {
                    getLogger().warning("Skipping invalid UUID in oak-log-progress: " + uuidString);
                }
            }
        }
    }

    private void saveData() {
        if (dataFile == null || data == null) return;

        data.set("players", null);
        for (Map.Entry<UUID, EnumMap<SkillType, SkillProgress>> entry : playerProgress.entrySet()) {
            String base = "players." + entry.getKey();
            String name = playerNames.get(entry.getKey());
            if (name != null) data.set(base + ".name", name);
            for (SkillType skill : SkillType.values()) {
                data.set(base + ".skills." + skill.name(),
                        entry.getValue().getOrDefault(skill, new SkillProgress(0L)).totalXp());
            }
        }
        data.set("placed-blocks", new ArrayList<>(placedBlocks));
        data.set("oak-log-progress", null);
        for (Map.Entry<UUID, Integer> entry : oakLogProgress.entrySet()) {
            data.set("oak-log-progress." + entry.getKey(), entry.getValue());
        }

        try {
            data.save(dataFile);
        } catch (IOException exception) {
            long now = System.currentTimeMillis();
            if (now - lastSaveWarningMillis > 60_000L) {
                getLogger().severe("Could not save data.yml: " + exception.getMessage());
                lastSaveWarningMillis = now;
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                send(sender, "player-only");
                return true;
            }
            if (!sender.hasPermission("surrealskills.use")) {
                send(sender, "no-permission");
                return true;
            }
            openMenu(player);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "help" -> {
                for (String line : getConfig().getStringList("messages.help")) sender.sendMessage(color(line));
            }
            case "stats" -> {
                if (!sender.hasPermission("surrealskills.use")) {
                    send(sender, "no-permission");
                    return true;
                }
                if (args.length >= 2) {
                    Player target = Bukkit.getPlayerExact(args[1]);
                    if (target == null) {
                        send(sender, "player-not-found");
                        return true;
                    }
                    showStats(sender, target.getUniqueId(), target.getName());
                } else if (sender instanceof Player player) {
                    showStats(sender, player.getUniqueId(), player.getName());
                } else {
                    sender.sendMessage(color("&cUsage: /skills stats <online-player>"));
                }
            }
            case "top" -> {
                if (!sender.hasPermission("surrealskills.use")) {
                    send(sender, "no-permission");
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(color("&cUsage: /skills top <skill>"));
                    return true;
                }
                SkillType skill = SkillType.fromInput(args[1]);
                if (skill == null) {
                    send(sender, "invalid-skill");
                    return true;
                }
                showTop(sender, skill);
            }
            case "reload" -> {
                if (!sender.hasPermission("surrealskills.admin")) {
                    send(sender, "no-permission");
                    return true;
                }
                reloadConfig();
                sender.sendMessage(prefix() + color(getConfig().getString("messages.reloaded", "&aConfiguration reloaded.")));
            }
            default -> {
                for (String line : getConfig().getStringList("messages.help")) sender.sendMessage(color(line));
            }
        }
        return true;
    }
}
