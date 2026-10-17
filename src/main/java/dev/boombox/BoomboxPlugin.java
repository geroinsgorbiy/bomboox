package dev.boombox;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;

public final class BoomboxPlugin extends JavaPlugin {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private TrackManager tracks;
    private PackManager pack;
    private BoomboxManager boomboxes;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            Files.createDirectories(getDataFolder().toPath().resolve("music"));
            Files.createDirectories(getDataFolder().toPath().resolve("pack-extra"));
        } catch (IOException e) {
            getLogger().warning("Не удалось создать папки плагина: " + e.getMessage());
        }

        tracks = new TrackManager(this);
        pack = new PackManager(this);
        boomboxes = new BoomboxManager(this);

        getServer().getPluginManager().registerEvents(new BoomboxListener(this), this);

        BoomboxCommand command = new BoomboxCommand(this);
        PluginCommand pc = getCommand("boombox");
        if (pc != null) {
            pc.setExecutor(command);
            pc.setTabCompleter(command);
        }

        pack.startServer();
        boomboxes.start();
        tracks.reloadAsync(() -> {
            boomboxes.resumeAll();
            pack.rebuildAsync(true, null);
        });
    }

    @Override
    public void onDisable() {
        if (boomboxes != null) {
            boomboxes.shutdown();
        }
        if (pack != null) {
            pack.stopServer();
        }
    }

    /** Перечитывает конфиг, треки и пересобирает ресурспак. */
    public void reloadAll(Runnable done) {
        reloadConfig();
        pack.restartServer();
        tracks.reloadAsync(() -> {
            boomboxes.resumeAll();
            pack.rebuildAsync(true, done);
        });
    }

    public TrackManager tracks() {
        return tracks;
    }

    public PackManager pack() {
        return pack;
    }

    public BoomboxManager boomboxes() {
        return boomboxes;
    }

    /** Сообщение из config.yml (messages.<key>) с подстановкой {ключ} -> значение. */
    public Component msg(String key, String... kv) {
        String raw = getConfig().getString("messages." + key);
        if (raw == null) {
            raw = "<red>[Boombox] нет сообщения: " + key;
        }
        raw = raw.replace("{prefix}", getConfig().getString("messages.prefix", ""));
        for (int i = 0; i + 1 < kv.length; i += 2) {
            raw = raw.replace("{" + kv[i] + "}", MM.escapeTags(kv[i + 1]));
        }
        return MM.deserialize(raw).decoration(TextDecoration.ITALIC, false);
    }

    /** MiniMessage-строка -> Component (без курсива). */
    public Component mm(String raw) {
        return MM.deserialize(raw).decoration(TextDecoration.ITALIC, false);
    }
}
