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

    /** Hash de la última skin subida a MineSkin para cada jugador, para no repetir la subida. */
    private final ConcurrentHashMap<String, String> hashes = new ConcurrentHashMap<>();
    private final Set<UUID> inProgress = ConcurrentHashMap.newKeySet();
    private File dataFile;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        client = new TLauncherClient(getLogger(),
                getConfig().getString("perfil-url"),
                getConfig().getString("skin-url"));
        ignored = getConfig().getStringList("ignorar-jugadores");
        respectManualSkin = getConfig().getBoolean("respetar-skin-manual", true);
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
                sender.sendMessage(ChatColor.RED + "No tienes permiso para cambiar la skin de otros.");
                return true;
            }
            target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                sender.sendMessage(ChatColor.RED + "Ese jugador no está conectado.");
                return true;
            }
        } else if (sender instanceof Player player) {
            if (!sender.hasPermission("tlskin.use")) {
                sender.sendMessage(ChatColor.RED + "No tienes permiso.");
                return true;
            }
            target = player;
        } else {
            sender.sendMessage("Uso: /tlskin <jugador>");
            return true;
        }

        sender.sendMessage(ChatColor.GRAY + "Buscando la skin de TLauncher de " + target.getName() + "...");
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> loadSkin(target, true, sender));
        return true;
    }

    /**
     * Descarga la skin de TLauncher del jugador y se la pone con SkinsRestorer.
     *
     * @param force  ignora la caché y la skin puesta a mano
     * @param sender a quién avisar del resultado (puede ser null)
     */
    private void loadSkin(Player player, boolean force, CommandSender sender) {
        String name = player.getName();
        UUID uuid = player.getUniqueId();
        if (!force && ignored.stream().anyMatch(name::equalsIgnoreCase)) {
            log(name + " está en la lista de ignorados.");
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
                log(name + " tiene una skin puesta a mano, no se toca.");
                return;
            }

            Optional<TLauncherClient.Skin> skin = client.fetch(name);
            if (skin.isEmpty()) {
                log(name + " no tiene skin en TLauncher.");
                reply(sender, ChatColor.RED + name + " no tiene skin en TLauncher.");
                return;
            }

            String hash = sha256(skin.get().png()) + ":" + skin.get().variant();
            boolean unchanged = hash.equals(hashes.get(uuid.toString()))
                    && skinsRestorer.getSkinStorage().getSkinDataByIdentifier(ourSkin).isPresent();

            if (!unchanged || force) {
                // MineSkin registra la imagen en Mojang y devuelve la textura firmada
                MineSkinResponse response = skinsRestorer.getMineSkinAPI()
                        .genSkin(skin.get().png(), skin.get().variant());
                skinsRestorer.getSkinStorage().setCustomSkinData(customName, response.getProperty());
                hashes.put(uuid.toString(), hash);
                saveData();
                log("Skin de " + name + " subida a MineSkin (" + response.getMineSkinId() + ").");
            } else if (hasOurSkin) {
                log("La skin de " + name + " no ha cambiado.");
                return;
            }

            playerStorage.setSkinIdOfPlayer(uuid, ourSkin);
            Bukkit.getScheduler().runTask(this, () -> {
                if (!player.isOnline()) {
                    return;
                }
                try {
                    skinsRestorer.getSkinApplier(Player.class).applySkin(player);
                    reply(sender, ChatColor.GREEN + "Skin de TLauncher aplicada a " + name + ".");
                } catch (Exception e) {
                    getLogger().log(Level.WARNING, "No se pudo aplicar la skin a " + name, e);
                }
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            getLogger().warning("Error cargando la skin de TLauncher de " + name + ": " + e);
            reply(sender, ChatColor.RED + "Error cargando la skin: " + e.getMessage());
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
            getLogger().log(Level.WARNING, "No se pudo guardar data.yml", e);
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
