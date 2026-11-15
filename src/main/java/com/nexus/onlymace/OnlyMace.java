package com.nexus.onlymace;

import org.bukkit.*;
import org.bukkit.block.Crafter;
import org.bukkit.boss.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.*;

public final class OnlyMace extends JavaPlugin implements Listener {
    private static final long RITUAL_MILLIS = 10L * 60L * 1000L;
    private static final int MACE_COOLDOWN_TICKS = 30 * 20;

    private BossBar bossBar;
    private ItemDisplay display;
    private TextDisplay timerDisplay;
    private BukkitTask ritualTask;
    private Location ritualOrigin;
    private long ritualEnd;
    private boolean claimed;

    @Override
    public void onEnable() {
        claimed = getConfig().getBoolean("mace-claimed", false);
        getServer().getPluginManager().registerEvents(this, this);
        bossBar = Bukkit.createBossBar("THE ONLY MACE RITUAL", BarColor.PURPLE, BarStyle.SEGMENTED_20);
        bossBar.setVisible(false);

        if (getConfig().getBoolean("ritual-active", false)) {
            String worldId = getConfig().getString("ritual-world", "");
            try {
                World world = Bukkit.getWorld(UUID.fromString(worldId));
                if (world != null) {
                    ritualOrigin = new Location(world, getConfig().getDouble("ritual-x"),
                            getConfig().getDouble("ritual-y"), getConfig().getDouble("ritual-z"));
                    ritualEnd = getConfig().getLong("ritual-end");
                    if (ritualEnd > System.currentTimeMillis()) {
                        spawnRitualVisuals();
                        for (Player p : Bukkit.getOnlinePlayers()) bossBar.addPlayer(p);
                        beginRitualTicker();
                    } else completeRitual();
                }
            } catch (IllegalArgumentException ignored) {
                getLogger().warning("Saved ritual world UUID is invalid; ritual could not resume.");
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("directgivemace")) return false;

        if (args.length != 1) {
            sender.sendMessage(ChatColor.RED + "Usage: /directgivemace <player>");
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "That player is not online.");
            return true;
        }

        if (claimed) {
            sender.sendMessage(ChatColor.RED + "The Only Mace has already been claimed.");
            return true;
        }

        // Claim the global mace before handing it out, so crafting and crafters
        // cannot start another ritual or create a second Only Mace.
        claimed = true;
        getConfig().set("mace-claimed", true);

        // If a ritual was already running, stop it so it cannot drop another mace.
        if (ritualTask != null) {
            ritualTask.cancel();
            ritualTask = null;
        }
        removeRitualVisuals();
        getConfig().set("ritual-active", false);
        saveConfig();

        if (bossBar != null) {
            bossBar.removeAll();
            bossBar.setVisible(false);
        }

        giveOrDrop(target, List.of(namedMace()));
        target.sendMessage(ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD
                + "You have received THE ONLY MACE!");
        sender.sendMessage(ChatColor.GREEN + "Gave THE ONLY MACE directly to "
                + target.getName() + " without a ritual timer.");
        return true;
    }

    @Override
    public void onDisable() {
        if (ritualTask != null) ritualTask.cancel();
        removeRitualVisuals();
        if (bossBar != null) {
            bossBar.removeAll();
            bossBar.setVisible(false);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBrew(BrewEvent event) { event.setCancelled(true); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPotionDrink(PlayerItemConsumeEvent event) {
        if (event.getItem().getType() == Material.POTION) {
            Player player = event.getPlayer();
            Bukkit.getScheduler().runTask(this, player::clearActivePotionEffects);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPotionSplash(PotionSplashEvent event) {
        event.setCancelled(true);
        if (event.getPotion().getShooter() instanceof Player player)
            Bukkit.getScheduler().runTask(this, player::clearActivePotionEffects);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCraftMace(CraftItemEvent event) {
        ItemStack current = event.getCurrentItem();
        if (current == null || current.getType() != Material.MACE) return;
        event.setCancelled(true);
        Player player = (Player) event.getWhoClicked();
        CraftingInventory inventory = event.getInventory();

        if (claimed) {
            Bukkit.getScheduler().runTask(this, () -> {
                List<ItemStack> returned = extractKeyIngredients(inventory.getMatrix());
                inventory.setMatrix(new ItemStack[inventory.getMatrix().length]);
                giveOrDrop(player, returned);
                player.sendMessage(ChatColor.RED + "The Only Mace has already been claimed. Your Heavy Core and Breeze Rods were returned.");
                player.updateInventory();
            });
            return;
        }

        Location base = inventory.getLocation();
        if (base == null || base.getWorld() == null) base = player.getLocation();
        Location ritualLocation = base.clone().add(0.5, 1.25, 0.5);
        claimed = true;
        getConfig().set("mace-claimed", true);
        saveConfig();

        Bukkit.getScheduler().runTask(this, () -> {
            inventory.setMatrix(new ItemStack[inventory.getMatrix().length]);
            player.updateInventory();
            startRitual(ritualLocation, player.getName());
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCrafterCraft(CrafterCraftEvent event) {
        if (event.getResult().getType() != Material.MACE) return;
        event.setCancelled(true);
        Location location = event.getBlock().getLocation().add(0.5, 1.25, 0.5);
        // CrafterCraftEvent runs on the server thread. Clear its live block inventory
        // immediately; scheduling this for the next tick lets the crafter retain its
        // ingredients while our returned items are already dropped, causing duplication.
        if (!(event.getBlock().getState() instanceof Crafter crafter)) return;

        if (claimed) {
            List<ItemStack> returned = extractKeyIngredients(crafter.getInventory().getContents());
            crafter.getInventory().clear();
            crafter.update(true, false);
            dropItems(location, returned);
            return;
        }

        claimed = true;
        getConfig().set("mace-claimed", true);
        saveConfig();

        crafter.getInventory().clear();
        crafter.update(true, false);
        startRitual(location, "The Autocrafter");
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void preventMaceHitDuringCooldown(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player
                && player.getInventory().getItemInMainHand().getType() == Material.MACE
                && player.hasCooldown(Material.MACE)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void applyMaceCooldown(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player
                && player.getInventory().getItemInMainHand().getType() == Material.MACE
                && player.getFallDistance() >= 3.0f) {
            player.setCooldown(Material.MACE, MACE_COOLDOWN_TICKS);
            player.sendMessage(ChatColor.LIGHT_PURPLE + "Mace cooldown: 30 seconds.");
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (bossBar != null && ritualTask != null) bossBar.addPlayer(event.getPlayer());
    }

    private void startRitual(Location location, String starter) {
        ritualOrigin = location.clone();
        ritualEnd = System.currentTimeMillis() + RITUAL_MILLIS;
        getConfig().set("ritual-active", true);
        getConfig().set("ritual-world", location.getWorld().getUID().toString());
        getConfig().set("ritual-x", location.getX());
        getConfig().set("ritual-y", location.getY());
        getConfig().set("ritual-z", location.getZ());
        getConfig().set("ritual-end", ritualEnd);
        getConfig().set("ritual-started-by", starter);
        saveConfig();
        for (Player p : Bukkit.getOnlinePlayers()) bossBar.addPlayer(p);
        bossBar.setVisible(true);
        spawnRitualVisuals();
        Bukkit.broadcastMessage(ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + "✦ THE ONLY MACE RITUAL HAS BEGUN! ✦");
        Bukkit.broadcastMessage(ChatColor.LIGHT_PURPLE + "A legendary weapon is forming. Collectible in 10 minutes.");
        beginRitualTicker();
    }

    private void spawnRitualVisuals() {
        removeRitualVisuals();
        if (ritualOrigin == null || ritualOrigin.getWorld() == null) return;
        display = (ItemDisplay) ritualOrigin.getWorld().spawnEntity(
                ritualOrigin.clone().add(0, 0.45, 0), EntityType.ITEM_DISPLAY);
        ItemStack mace = namedMace();
        display.setItemStack(mace);
        display.setTransformation(new Transformation(new Vector3f(0, 0, 0),
                new AxisAngle4f(0, 0, 1, 0), new Vector3f(3, 3, 3),
                new AxisAngle4f(0, 0, 1, 0)));
        display.setBillboard(Display.Billboard.CENTER);
        display.setInvulnerable(true);
        display.setPersistent(false);

        timerDisplay = (TextDisplay) ritualOrigin.getWorld().spawnEntity(
                ritualOrigin.clone().add(0, 3.3, 0), EntityType.TEXT_DISPLAY);
        timerDisplay.setBillboard(Display.Billboard.CENTER);
        timerDisplay.setSeeThrough(true);
        timerDisplay.setShadowed(true);
        timerDisplay.setDefaultBackground(false);
        timerDisplay.setPersistent(false);
        timerDisplay.setText(ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + "10:00");
        bossBar.setVisible(true);
    }

    private void beginRitualTicker() {
        if (ritualTask != null) ritualTask.cancel();
        ritualTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (ritualOrigin == null || ritualOrigin.getWorld() == null) return;
            long remaining = Math.max(0, ritualEnd - System.currentTimeMillis());
            bossBar.setProgress(Math.max(0, Math.min(1, 1.0 - (double) remaining / RITUAL_MILLIS)));
            bossBar.setTitle(ChatColor.LIGHT_PURPLE + "ONLY MACE RITUAL  " + ChatColor.WHITE
                    + "X: " + fmt(ritualOrigin.getX()) + "  Y: " + fmt(ritualOrigin.getY())
                    + "  Z: " + fmt(ritualOrigin.getZ()));
            long seconds = (remaining + 999) / 1000;
            if (timerDisplay != null && timerDisplay.isValid())
                timerDisplay.setText(ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD
                        + String.format("%02d:%02d", seconds / 60, seconds % 60)
                        + "\n" + ChatColor.WHITE + "THE ONLY MACE");
            double angle = System.currentTimeMillis() / 500.0;
            if (display != null && display.isValid()) {
                display.teleport(ritualOrigin.clone().add(Math.cos(angle) * 0.32,
                        0.45 + Math.sin(angle * 2) * 0.18, Math.sin(angle) * 0.32));
                display.setRotation((float) ((angle * 35) % 360), 0);
            }
            spawnAura();
            if (remaining <= 0) completeRitual();
        }, 1L, 5L);
    }

    private void spawnAura() {
        World world = ritualOrigin.getWorld();
        double t = System.currentTimeMillis() / 450.0;
        for (int i = 0; i < 28; i++) {
            double a = Math.PI * 2 * i / 28.0 + t;
            double radius = 1.75 + 0.25 * Math.sin(t * 1.7 + i);
            Location p = ritualOrigin.clone().add(Math.cos(a) * radius,
                    0.8 + 0.55 * Math.sin(a * 2 + t), Math.sin(a) * radius);
            world.spawnParticle(Particle.END_ROD, p, 1, 0, 0, 0, 0);
            if (i % 2 == 0) world.spawnParticle(Particle.SOUL_FIRE_FLAME, p, 1, 0.04, 0.08, 0.04, 0.005);
            if (i % 4 == 0) world.spawnParticle(Particle.PORTAL, p, 2, 0.12, 0.25, 0.12, 0.02);
        }
        world.spawnParticle(Particle.ENCHANT, ritualOrigin.clone().add(0, 1.1, 0), 18, 0.8, 1.1, 0.8, 1.0);
        world.spawnParticle(Particle.ELECTRIC_SPARK, ritualOrigin.clone().add(0, 1, 0), 8, 0.55, 0.8, 0.55, 0.04);
    }

    private void completeRitual() {
        if (ritualTask != null) { ritualTask.cancel(); ritualTask = null; }
        if (ritualOrigin == null || ritualOrigin.getWorld() == null) return;
        removeRitualVisuals();
        Item dropped = ritualOrigin.getWorld().dropItemNaturally(ritualOrigin.clone().add(0, 1, 0), namedMace());
        dropped.setCustomName(ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + "THE ONLY MACE");
        dropped.setCustomNameVisible(true);
        dropped.setGlowing(true);
        dropped.setUnlimitedLifetime(true);
        getConfig().set("ritual-active", false);
        saveConfig();
        bossBar.setVisible(false);
        bossBar.removeAll();
        Bukkit.broadcastMessage(ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + "✦ THE RITUAL IS COMPLETE! ✦");
        Bukkit.broadcastMessage(ChatColor.LIGHT_PURPLE
                + getConfig().getString("ritual-started-by", "A player") + " received the Only Mace!");
        ritualOrigin.getWorld().spawnParticle(Particle.FIREWORK,
                ritualOrigin.clone().add(0, 1, 0), 100, 1.3, 1.5, 1.3, 0.18);
        ritualOrigin.getWorld().playSound(ritualOrigin, Sound.UI_TOAST_CHALLENGE_COMPLETE, 2, 0.65f);
    }

    private ItemStack namedMace() {
        ItemStack mace = new ItemStack(Material.MACE);
        ItemMeta meta = mace.getItemMeta();
        meta.setDisplayName(ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + "THE ONLY MACE");
        meta.setLore(List.of(ChatColor.GRAY + "A weapon forged by a 10-minute ritual.",
                ChatColor.DARK_PURPLE + "The one and only."));
        mace.setItemMeta(meta);
        return mace;
    }

    private void removeRitualVisuals() {
        if (display != null && display.isValid()) display.remove();
        if (timerDisplay != null && timerDisplay.isValid()) timerDisplay.remove();
        display = null; timerDisplay = null;
    }

    private List<ItemStack> extractKeyIngredients(ItemStack[] matrix) {
        int cores = 0, rods = 0;
        for (ItemStack item : matrix) {
            if (item == null || item.getType().isAir()) continue;
            if (item.getType() == Material.HEAVY_CORE) cores += item.getAmount();
            if (item.getType() == Material.BREEZE_ROD) rods += item.getAmount();
        }
        List<ItemStack> result = new ArrayList<>();
        if (cores > 0) result.add(new ItemStack(Material.HEAVY_CORE, cores));
        if (rods > 0) result.add(new ItemStack(Material.BREEZE_ROD, rods));
        return result;
    }

    private void giveOrDrop(Player player, List<ItemStack> items) {
        for (ItemStack item : items) {
            for (ItemStack leftover : player.getInventory().addItem(item).values())
                player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
    }

    private void dropItems(Location location, List<ItemStack> items) {
        for (ItemStack item : items) location.getWorld().dropItemNaturally(location, item);
    }

    private String fmt(double value) { return String.format(Locale.US, "%.1f", value); }
}
