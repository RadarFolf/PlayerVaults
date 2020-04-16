/*
 * PlayerVaultsX
 * Copyright (C) 2013 Trent Hensler
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.drtshock.playervaults;

import co.aikar.taskchain.BukkitTaskChainFactory;
import co.aikar.taskchain.TaskChain;
import co.aikar.taskchain.TaskChainFactory;
import com.drtshock.playervaults.commands.ConvertCommand;
import com.drtshock.playervaults.commands.DeleteCommand;
import com.drtshock.playervaults.commands.SignCommand;
import com.drtshock.playervaults.commands.SignSetInfo;
import com.drtshock.playervaults.commands.VaultCommand;
import com.drtshock.playervaults.config.Loader;
import com.drtshock.playervaults.config.file.Config;
import com.drtshock.playervaults.listeners.Listeners;
import com.drtshock.playervaults.listeners.SignListener;
import com.drtshock.playervaults.listeners.VaultPreloadListener;
import com.drtshock.playervaults.tasks.Cleanup;
import com.drtshock.playervaults.translations.Lang;
import com.drtshock.playervaults.translations.Language;
import com.drtshock.playervaults.vaultmanagement.VaultManager;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.permission.Permission;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import java.util.logging.Level;

public class PlayerVaults extends JavaPlugin {
    public static boolean DEBUG;
    private static PlayerVaults instance;
    private final HashMap<String, SignSetInfo> setSign = new HashMap<>();

    private final Set<Material> blockedMats = new HashSet<>();
    private Economy economy;
    private Permission permission;
    private YamlConfiguration signs;
    private File signsFile;
    private boolean saveQueued;
    private boolean backupsEnabled;
    private File backupsFolder;
    private File uuidData;
    private File vaultData;
    private String _versionString;
    private int maxVaultAmountPermTest;
    private Metrics metrics;
    private final Config config = new Config();
    private TaskChainFactory taskChainFactory;

    public static PlayerVaults getInstance() {
        return instance;
    }

    public static void debug(@NonNull String s, long start) {
        if (DEBUG) {
            instance.getLogger().log(Level.INFO, "{0} took {1}ms", new Object[]{s, (System.currentTimeMillis() - start)});
        }
    }

    public static void debug(String s) {
        if (DEBUG) {
            instance.getLogger().log(Level.INFO, s);
        }
    }

    @Override
    public void onEnable() {
        instance = this;
        this.taskChainFactory = BukkitTaskChainFactory.create(this);
        long start = System.currentTimeMillis();
        long time = System.currentTimeMillis();
        loadConfig();
        DEBUG = getConf().isDebug();
        debug("config", time);
        time = System.currentTimeMillis();
        uuidData = new File(this.getDataFolder(), "uuidvaults");
        vaultData = new File(this.getDataFolder(), "base64vaults");
        debug("vaultdata", time);
        new VaultManager();
        time = System.currentTimeMillis();
        loadLang();
        debug("lang", time);
        time = System.currentTimeMillis();
        getServer().getPluginManager().registerEvents(new Listeners(this), this);
        getServer().getPluginManager().registerEvents(new VaultPreloadListener(), this);
        getServer().getPluginManager().registerEvents(new SignListener(this), this);
        debug("registering listeners", time);
        time = System.currentTimeMillis();
        this.backupsEnabled = this.getConf().getStorage().getFlatFile().isBackups();
        this.maxVaultAmountPermTest = this.getConf().getMaxVaultAmountPermTest();
        loadSigns();
        debug("loaded signs", time);
        time = System.currentTimeMillis();
        getCommand("pv").setExecutor(new VaultCommand());
        getCommand("pvdel").setExecutor(new DeleteCommand());
        getCommand("pvconvert").setExecutor(new ConvertCommand());
        getCommand("pvsign").setExecutor(new SignCommand());
        debug("registered commands", time);
        time = System.currentTimeMillis();
        Plugin vaultPlugin = getServer().getPluginManager().getPlugin("Vault");
        if (vaultPlugin == null || this.vaultBad(vaultPlugin.getDescription().getVersion())) { // Argh!
            this.getLogger().severe("This plugin requires Vault of at least version 1.7!");
            this.getServer().getPluginManager().disablePlugin(this);
            return;
        }

        RegisteredServiceProvider<Economy> providerE = getServer().getServicesManager().getRegistration(Economy.class);
        if (providerE != null) {
            this.economy = providerE.getProvider();
            this.getLogger().info("Found economy integration " + economy.getName() + " and planning to " + (this.getConf().getEconomy().isEnabled() ? "use" : "not use") + " it.");
        }
        RegisteredServiceProvider<Permission> providerP = getServer().getServicesManager().getRegistration(Permission.class);
        if (providerP == null) { // ... how? It should do superperms by default
            this.getLogger().severe("This plugin requires Vault to link to a permissions plugin or built-in permissions.");
            this.getServer().getPluginManager().disablePlugin(this);
            return;
        }
        this.permission = providerP.getProvider();
        this.getLogger().info("Using permission integration " + permission.getName());

        debug("setup vault", time);

        if (getConf().getPurge().isEnabled()) {
            getServer().getScheduler().runTaskAsynchronously(this, new Cleanup(getConf().getPurge().getDaysSinceLastEdit()));
        }

        new BukkitRunnable() {
            @Override
            public void run() {
                if (saveQueued) {
                    saveSignsFile();
                }
            }
        }.runTaskTimer(this, 20, 20);

        this.metrics = new Metrics(this, 6905);
        Plugin vault = getServer().getPluginManager().getPlugin("Vault");
        this.metricsDrillPie("vault", () -> this.metricsPluginInfo(vault));
        if (vault != null) {
            this.metricsDrillPie("vault_econ", () -> {
                Map<String, Map<String, Integer>> map = new HashMap<>();
                Map<String, Integer> entry = new HashMap<>();
                entry.put(economy == null ? "none" : economy.getName(), 1);
                map.put(isEconomyEnabled() ? "enabled" : "disabled", entry);
                return map;
            });
            if (isEconomyEnabled()) {
                String name = economy.getName();
                if (name.equals("Essentials Economy")) {
                    name = "Essentials";
                }
                Plugin plugin = getServer().getPluginManager().getPlugin(name);
                if (plugin != null) {
                    this.metricsDrillPie("vault_econ_plugins", () -> {
                        Map<String, Map<String, Integer>> map = new HashMap<>();
                        Map<String, Integer> entry = new HashMap<>();
                        entry.put(plugin.getDescription().getVersion(), 1);
                        map.put(plugin.getName(), entry);
                        return map;
                    });
                }
            }
        }

        if (vault != null) {
            RegisteredServiceProvider<Permission> provider = getServer().getServicesManager().getRegistration(Permission.class);
            if (provider != null) {
                Permission perm = provider.getProvider();
                String name = perm.getName();
                Plugin plugin = getServer().getPluginManager().getPlugin(name);
                final String version;
                if (plugin == null) {
                    version = "unknown";
                } else {
                    version = plugin.getDescription().getVersion();
                }
                this.metricsDrillPie("vault_perms", () -> {
                    Map<String, Map<String, Integer>> map = new HashMap<>();
                    Map<String, Integer> entry = new HashMap<>();
                    entry.put(version, 1);
                    map.put(name, entry);
                    return map;
                });
            }
        }

        this.metricsSimplePie("signs", () -> getConf().isSigns() ? "enabled" : "disabled");
        this.metricsSimplePie("cleanup", () -> getConf().getPurge().isEnabled() ? "enabled" : "disabled");
        this.metricsSimplePie("language", () -> getConf().getLanguage());

        this.metricsDrillPie("block_items", () -> {
            Map<String, Map<String, Integer>> map = new HashMap<>();
            Map<String, Integer> entry = new HashMap<>();
            if (getConf().getItemBlocking().isEnabled()) {
                for (Material material : blockedMats) {
                    entry.put(material.toString(), 1);
                }
            }
            if (entry.isEmpty()) {
                entry.put("none", 1);
            }
            map.put(getConf().getItemBlocking().isEnabled() ? "enabled" : "disabled", entry);
            return map;
        });

        this.getLogger().info("Loaded! Took " + (System.currentTimeMillis() - start) + "ms");
    }

    private boolean vaultBad(@NonNull String version) {
        String[] split = version.split("\\.");
        try {
            return Integer.parseInt(split[1]) < 7;
        } catch (Exception ignored) {
            return true;
        }
    }

    private void metricsLine(@NonNull String name, @NonNull Callable<Integer> callable) {
        this.metrics.addCustomChart(new Metrics.SingleLineChart(name, callable));
    }

    private void metricsDrillPie(@NonNull String name, @NonNull Callable<Map<String, Map<String, Integer>>> callable) {
        this.metrics.addCustomChart(new Metrics.DrilldownPie(name, callable));
    }

    private void metricsSimplePie(@NonNull String name, @NonNull Callable<String> callable) {
        this.metrics.addCustomChart(new Metrics.SimplePie(name, callable));
    }

    private @NonNull Map<String, Map<String, Integer>> metricsPluginInfo(@NonNull Plugin plugin) {
        return this.metricsInfo(plugin, () -> plugin.getDescription().getVersion());
    }

    private @NonNull Map<String, Map<String, Integer>> metricsInfo(@Nullable Object plugin, @NonNull Supplier<String> versionGetter) {
        Map<String, Map<String, Integer>> map = new HashMap<>();
        Map<String, Integer> entry = new HashMap<>();
        entry.put(plugin == null ? "nope" : versionGetter.get(), 1);
        map.put(plugin == null ? "absent" : "present", entry);
        return map;
    }

    @Override
    public void onDisable() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (this.inVault.containsKey(player.getUniqueId().toString())) {
                Inventory inventory = player.getOpenInventory().getTopInventory();
                if (inventory.getViewers().size() == 1) {
                    VaultViewInfo info = this.inVault.get(player.getUniqueId().toString());
                    VaultManager.getInstance().saveVault(inventory, player.getUniqueId().toString(), info.getNumber());
                    this.openInventories.remove(info.toString());
                    // try this to make sure that they can't make further edits if the process hangs.
                    player.closeInventory();
                }

                this.inVault.remove(player.getUniqueId().toString());
                debug("Closing vault for " + player.getName());
                player.closeInventory();
            }
        }

        if (getConf().getPurge().isEnabled()) {
            saveSignsFile();
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("pvreload")) {
            reloadConfig();
            loadConfig(); // To update blocked materials.
            reloadSigns();
            loadLang();
            sender.sendMessage(ChatColor.GREEN + "Reloaded PlayerVault's configuration and lang files.");
        }
        return true;
    }

    private void loadConfig() {
        File configYaml = new File(this.getDataFolder(), "config.yml");
        if (!(new File(this.getDataFolder(), "config.conf").exists()) && configYaml.exists()) {
            this.config.setFromConfig(this.getLogger(), this.getConfig());
            try {
                Files.move(configYaml.toPath(), this.getDataFolder().toPath().resolve("old_unused_config.yml"));
            } catch (Exception e) {
                this.getLogger().log(Level.SEVERE, "Failed to move config for backup", e);
                configYaml.deleteOnExit();
            }
        }

        try {
            Loader.loadAndSave("config", this.config);
        } catch (IOException | IllegalAccessException e) {
            this.getLogger().log(Level.SEVERE, "Could not load config.", e);
        }

        // Clear just in case this is a reload.
        blockedMats.clear();
        if (getConf().getItemBlocking().isEnabled()) {
            for (String s : getConf().getItemBlocking().getList()) {
                Material mat = Material.matchMaterial(s);
                if (mat != null) {
                    blockedMats.add(mat);
                    getLogger().log(Level.INFO, "Added {0} to list of blocked materials.", mat.name());
                }
            }
        }
    }

    public @NonNull Config getConf() {
        return this.config;
    }

    private void loadSigns() {
        File signs = new File(getDataFolder(), "signs.yml");
        if (!signs.exists()) {
            try {
                signs.createNewFile();
            } catch (IOException e) {
                getLogger().severe("PlayerVaults has encountered a fatal error trying to load the signs file.");
                getLogger().severe("Please report this error on GitHub @ https://github.com/drtshock/PlayerVaults/");
                e.printStackTrace();
            }
        }
        this.signsFile = signs;
        this.signs = YamlConfiguration.loadConfiguration(signs);
    }

    private void reloadSigns() {
        if (!getConf().isSigns()) {
            return;
        }
        if (!signsFile.exists()) loadSigns();
        try {
            signs.load(signsFile);
        } catch (IOException | InvalidConfigurationException e) {
            getLogger().severe("PlayerVaults has encountered a fatal error trying to reload the signs file.");
            getLogger().severe("Please report this error on GitHub @ https://github.com/drtshock/PlayerVaults/");
            e.printStackTrace();
        }
    }

    /**
     * Get the signs.yml config.
     *
     * @return The signs.yml config.
     */
    public @NonNull YamlConfiguration getSigns() {
        return this.signs;
    }

    /**
     * Save the signs.yml file.
     */
    public void saveSigns() {
        saveQueued = true;
    }

    private void saveSignsFile() {
        if (!getConf().isSigns()) {
            return;
        }

        saveQueued = false;
        try {
            signs.save(this.signsFile);
        } catch (IOException e) {
            getLogger().severe("PlayerVaults has encountered an error trying to save the signs file.");
            getLogger().severe("Please report this error on GitHub @ https://github.com/drtshock/PlayerVaults/");
            e.printStackTrace();
        }
    }

    public void loadLang() {
        File folder = new File(getDataFolder(), "lang");
        if (!folder.exists()) {
            folder.mkdir();
        }

        String definedLanguage = getConf().getLanguage();

        // Save as default just incase.
        File english = null;
        File definedFile = null;

        for (Language lang : Language.values()) {
            String fileName = lang.getFriendlyName() + ".yml";
            File file = new File(folder, fileName);
            if (lang == Language.ENGLISH) {
                english = file;
            }

            if (definedLanguage.equalsIgnoreCase(lang.getFriendlyName())) {
                definedFile = file;
            }

            // Have Bukkit save the file.
            if (!file.exists()) {
                saveResource("lang/" + fileName, false);
            }
        }

        if (definedFile != null && !definedFile.exists()) {
            getLogger().severe("Failed to load language for " + definedLanguage + ". Defaulting to English.");
            definedFile = english;
        }

        if (definedFile == null) {
            getLogger().severe("Failed to load custom language settings. Loading plugin defaults. This should never happen, go ask for help.");
            return;
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(definedFile);
        Lang.setFile(config);
        getLogger().info("Loaded lang for " + definedLanguage);
    }

    public @NonNull HashMap<String, SignSetInfo> getSetSign() {
        return this.setSign;
    }

    public @Nullable Economy getEconomy() {
        return this.economy;
    }

    public boolean isEconomyEnabled() {
        return this.getConf().getEconomy().isEnabled() && this.economy != null;
    }

    public @NonNull File getVaultData() {
        return this.vaultData;
    }

    /**
     * Get the legacy UUID vault data folder.
     * Deprecated in favor of base64 data.
     *
     * @return
     */
    @Deprecated
    public File getUuidData() {
        return this.uuidData;
    }

    public boolean isBackupsEnabled() {
        return this.backupsEnabled;
    }

    public @NonNull File getBackupsFolder() {
        // having this in #onEnable() creates the 'uuidvaults' directory, preventing the conversion from running
        if (this.backupsFolder == null) {
            this.backupsFolder = new File(this.getVaultData(), "backups");
            this.backupsFolder.mkdirs();
        }

        return this.backupsFolder;
    }

    public boolean isBlockedMaterial(@NonNull Material mat) {
        return blockedMats.contains(mat);
    }

    /**
     * Tries to grab the server version as a string.
     *
     * @return Version as raw string
     */
    public @NonNull String getVersion() {
        if (_versionString == null) {
            final String name = Bukkit.getServer().getClass().getPackage().getName();
            _versionString = name.substring(name.lastIndexOf(46) + 1) + ".";
        }
        return _versionString;
    }

    public int getDefaultVaultRows() {
        int def = this.config.getDefaultVaultRows();
        return (def >= 1 && def <= 6) ? def : 6;
    }

    public int getDefaultVaultSize() {
        return this.getDefaultVaultRows() * 9;
    }

    public boolean isSign(Material mat) {
        return mat.name().toUpperCase().contains("SIGN");
    }

    public int getMaxVaultAmountPermTest() {
        return this.maxVaultAmountPermTest;
    }

    public <T> @NonNull TaskChain<T> newChain() {
        return this.taskChainFactory.newChain();
    }

    public <T> @NonNull TaskChain<T> newSharedChain(@NonNull String name) {
        return this.taskChainFactory.newSharedChain(name);
    }

    /**
     * Checks if a particular name is a shared chain name.
     *
     * @return true if a shared chain name
     */
    public boolean isSharedChain(@NonNull String name) {
        Map<String, ?> sharedChains = this.taskChainFactory.getSharedChains();
        synchronized (sharedChains) {
            return sharedChains.containsKey(name);
        }
    }

    public @NonNull Permission getVaultPermission() {
        return this.permission;
    }
}
