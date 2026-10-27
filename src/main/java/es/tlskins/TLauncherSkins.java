package es.tlskins;

import net.skinsrestorer.api.SkinsRestorer;
import net.skinsrestorer.api.SkinsRestorerProvider;
import net.skinsrestorer.api.connections.model.MineSkinResponse;
import net.skinsrestorer.api.property.SkinIdentifier;
import net.skinsrestorer.api.storage.PlayerStorage;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class TLauncherSkins extends JavaPlugin implements Listener {

    private SkinsRestorer skinsRestorer;
    private TLauncherClient client;
    private List<String> ignored;
    private boolean respectManualSkin;
    private boolean debug;

    /** Hash of the last skin uploaded to MineSkin for each player, so it is not uploaded twice. */
    private final ConcurrentHashMap<String, String> hashes = new ConcurrentHashMap<>();
    private final Set<UUID> inProgress = ConcurrentHashMap.newKeySet();
    private File dataFile;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        client = new TLauncherClient(getLogger(),
                getConfig().getString("profile-url"),
                getConfig().getString("skin-url"));
        ignored = getConfig().getStringList("ignored-players");
        respectManualSkin = getConfig().getBoolean("respect-manual-skin", true);
        debug = getConfig().getBoolean("debug", false);

        dataFile = new File(getDataFolder(), "data.yml");
        YamlConfiguration data = YamlConfiguration.loadConfiguration(dataFile);
        for (String key : data.getKeys(false)) {
            hashes.put(key, data.getString(key));
        }

        skinsRestorer = SkinsRestorerProvider.get();
        getServer().getPluginManager().registerEvents(this, this);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> loadSkin(player, false, null));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Player target;
        if (args.length > 0) {
            if (!sender.hasPermission("tlskin.admin")) {
                sender.sendMessage(ChatColor.RED + "You don't have permission to change other players' skins.");
                return true;
            }
            target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                sender.sendMessage(ChatColor.RED + "That player is not online.");
                return true;
            }
        } else if (sender instanceof Player player) {
            if (!sender.hasPermission("tlskin.use")) {
                sender.sendMessage(ChatColor.RED + "You don't have permission.");
                return true;
            }
            target = player;
        } else {
            sender.sendMessage("Usage: /tlskin <player>");
            return true;
        }

        sender.sendMessage(ChatColor.GRAY + "Looking up the TLauncher skin of " + target.getName() + "...");
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> loadSkin(target, true, sender));
        return true;
    }

    /**
     * Downloads the player's TLauncher skin and applies it with SkinsRestorer.
     *
     * @param force  ignore the cache and any manually set skin
     * @param sender who to notify of the result (may be null)
     */
    private void loadSkin(Player player, boolean force, CommandSender sender) {
        String name = player.getName();
        UUID uuid = player.getUniqueId();
        if (!force && ignored.stream().anyMatch(name::equalsIgnoreCase)) {
            log(name + " is in the ignored list.");
            return;
        }
        if (!inProgress.add(uuid)) {
            return;
        }

        try {
            PlayerStorage playerStorage = skinsRestorer.getPlayerStorage();
            String customName = "tlauncher_" + name.toLowerCase();
            SkinIdentifier ourSkin = SkinIdentifier.ofCustom(customName);

            Optional<SkinIdentifier> current = playerStorage.getSkinIdOfPlayer(uuid);
            boolean hasOurSkin = current.isPresent() && current.get().equals(ourSkin);
            if (!force && respectManualSkin && current.isPresent() && !hasOurSkin) {
                log(name + " has a manually set skin, leaving it alone.");
                return;
            }

            Optional<TLauncherClient.Skin> skin = client.fetch(name);
            if (skin.isEmpty()) {
                log(name + " has no skin on TLauncher.");
                reply(sender, ChatColor.RED + name + " has no skin on TLauncher.");
                return;
            }

            String hash = sha256(skin.get().png()) + ":" + skin.get().variant();
            boolean unchanged = hash.equals(hashes.get(uuid.toString()))
                    && skinsRestorer.getSkinStorage().getSkinDataByIdentifier(ourSkin).isPresent();

            if (!unchanged || force) {
                // MineSkin registers the image with Mojang and returns the signed texture
                MineSkinResponse response = skinsRestorer.getMineSkinAPI()
                        .genSkin(skin.get().png(), skin.get().variant());
                skinsRestorer.getSkinStorage().setCustomSkinData(customName, response.getProperty());
                hashes.put(uuid.toString(), hash);
                saveData();
                log("Uploaded " + name + "'s skin to MineSkin (" + response.getMineSkinId() + ").");
            } else if (hasOurSkin) {
                log(name + "'s skin has not changed.");
                return;
            }

            playerStorage.setSkinIdOfPlayer(uuid, ourSkin);
            Bukkit.getScheduler().runTask(this, () -> {
                if (!player.isOnline()) {
                    return;
                }
                try {
                    skinsRestorer.getSkinApplier(Player.class).applySkin(player);
                    reply(sender, ChatColor.GREEN + "TLauncher skin applied to " + name + ".");
                } catch (Exception e) {
                    getLogger().log(Level.WARNING, "Could not apply the skin to " + name, e);
                }
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            getLogger().warning("Error loading the TLauncher skin of " + name + ": " + e);
            reply(sender, ChatColor.RED + "Error loading the skin: " + e.getMessage());
        } finally {
            inProgress.remove(uuid);
        }
    }

    private synchronized void saveData() {
        YamlConfiguration data = new YamlConfiguration();
        hashes.forEach(data::set);
        try {
            data.save(dataFile);
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "Could not save data.yml", e);
        }
    }

    private void reply(CommandSender sender, String message) {
        if (sender != null) {
            Bukkit.getScheduler().runTask(this, () -> sender.sendMessage(message));
        }
    }

    private void log(String message) {
        if (debug) {
            getLogger().info(message);
        }
    }

    private static String sha256(byte[] data) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }
}
