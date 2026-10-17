package dev.boombox;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

public final class BoomboxCommand implements CommandExecutor, TabCompleter {

    private final BoomboxPlugin plugin;

    public BoomboxCommand(BoomboxPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (a.length == 0) {
            help(s);
            return true;
        }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "give" -> give(s, a);
            case "list" -> list(s);
            case "add" -> add(s, a);
            case "remove" -> remove(s, a);
            case "reload" -> reload(s);
            case "pack" -> pack(s);
            default -> help(s);
        }
        return true;
    }

    private void help(CommandSender s) {
        for (int i = 1; i <= 7; i++) {
            s.sendMessage(plugin.msg("help-" + i));
        }
    }

    private void give(CommandSender s, String[] a) {
        if (!s.hasPermission("boombox.admin")) {
            s.sendMessage(plugin.msg("no-permission"));
            return;
        }
        Player target = null;
        if (a.length >= 2) {
            target = Bukkit.getPlayerExact(a[1]);
        } else if (s instanceof Player self) {
            target = self;
        }
        if (target == null) {
            s.sendMessage(plugin.msg("player-not-found"));
            return;
        }
        Map<Integer, ItemStack> left = target.getInventory().addItem(plugin.boomboxes().createItem());
        for (ItemStack rest : left.values()) {
            target.getWorld().dropItemNaturally(target.getLocation(), rest);
        }
        s.sendMessage(plugin.msg("given", "player", target.getName()));
    }

    private void list(CommandSender s) {
        List<TrackManager.Track> list = plugin.tracks().list();
        if (list.isEmpty()) {
            s.sendMessage(plugin.msg("tracks-empty"));
            return;
        }
        s.sendMessage(plugin.msg("tracks-header", "count", String.valueOf(list.size())));
        for (TrackManager.Track t : list) {
            s.sendMessage(plugin.msg("track-line", "id", t.id(), "name", t.name(), "time", TrackManager.fmt(t.seconds())));
        }
    }

    private void add(CommandSender s, String[] a) {
        if (!s.hasPermission("boombox.upload")) {
            s.sendMessage(plugin.msg("no-permission"));
            return;
        }
        if (a.length < 2) {
            s.sendMessage(plugin.msg("usage-add"));
            return;
        }
        String url = a[1];
        String name = a.length > 2 ? String.join(" ", Arrays.copyOfRange(a, 2, a.length)) : null;
        s.sendMessage(plugin.msg("add-started"));

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            TrackManager.Track added = null;
            String error = null;
            try {
                Path saved = plugin.tracks().download(url, name);
                plugin.tracks().scan();
                added = plugin.tracks().findByName(TrackManager.stripExt(saved.getFileName().toString()));
                if (added == null) {
                    error = "не удалось получить .ogg: для mp3/wav нужен ffmpeg на сервере, либо дай ссылку на .ogg (Vorbis)";
                } else {
                    plugin.pack().build();
                }
            } catch (Exception ex) {
                error = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            }
            TrackManager.Track fAdded = added;
            String fError = error;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (fError != null) {
                    s.sendMessage(plugin.msg("add-failed", "error", fError));
                } else {
                    s.sendMessage(plugin.msg("add-done", "id", fAdded.id(), "name", fAdded.name()));
                    plugin.pack().sendToAll();
                }
            });
        });
    }

    private void remove(CommandSender s, String[] a) {
        if (!s.hasPermission("boombox.upload")) {
            s.sendMessage(plugin.msg("no-permission"));
            return;
        }
        if (a.length < 2) {
            s.sendMessage(plugin.msg("usage-remove"));
            return;
        }
        TrackManager.Track t = plugin.tracks().get(a[1].toLowerCase(Locale.ROOT));
        if (t == null) {
            s.sendMessage(plugin.msg("track-unknown", "id", a[1]));
            return;
        }
        plugin.boomboxes().stopTrack(t.id());

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String error = null;
            try {
                Files.deleteIfExists(t.file());
                plugin.tracks().scan();
                plugin.pack().build();
            } catch (Exception ex) {
                error = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            }
            String fError = error;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (fError != null) {
                    s.sendMessage(plugin.msg("add-failed", "error", fError));
                } else {
                    s.sendMessage(plugin.msg("removed", "id", t.id()));
                    plugin.pack().sendToAll();
                }
            });
        });
    }

    private void reload(CommandSender s) {
        if (!s.hasPermission("boombox.admin")) {
            s.sendMessage(plugin.msg("no-permission"));
            return;
        }
        plugin.reloadAll(() -> s.sendMessage(plugin.msg("reloaded")));
    }

    private void pack(CommandSender s) {
        if (!(s instanceof Player p)) {
            s.sendMessage(plugin.msg("players-only"));
            return;
        }
        if (plugin.pack().sendTo(p)) {
            p.sendMessage(plugin.msg("pack-sent"));
        } else {
            p.sendMessage(plugin.msg("pack-not-ready"));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] a) {
        if (a.length == 1) {
            List<String> subs = new ArrayList<>(List.of("list", "pack"));
            if (s.hasPermission("boombox.admin")) {
                subs.add("give");
                subs.add("reload");
            }
            if (s.hasPermission("boombox.upload")) {
                subs.add("add");
                subs.add("remove");
            }
            return filter(subs, a[0]);
        }
        if (a.length == 2) {
            String sub = a[0].toLowerCase(Locale.ROOT);
            if (sub.equals("give") && s.hasPermission("boombox.admin")) {
                List<String> names = new ArrayList<>();
                for (Player p : Bukkit.getOnlinePlayers()) {
                    names.add(p.getName());
                }
                return filter(names, a[1]);
            }
            if (sub.equals("remove") && s.hasPermission("boombox.upload")) {
                List<String> ids = new ArrayList<>();
                for (TrackManager.Track t : plugin.tracks().list()) {
                    ids.add(t.id());
                }
                return filter(ids, a[1]);
            }
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(p)).collect(Collectors.toList());
    }
}
