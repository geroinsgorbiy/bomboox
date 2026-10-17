package dev.boombox;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Предмет-бумбокс, постановка в мир (ItemDisplay + Interaction) и воспроизведение музыки.
 *
 * Музыка шлётся каждому игроку рядом отдельным пакетом playSound; когда игрок уходит
 * дальше радиуса — звук останавливается, когда приходит — запускается заново с начала трека.
 */
public final class BoomboxManager {

    public enum Repeat {
        OFF, ONE, ALL;

        public Repeat next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    private static final class Playback {
        final UUID id;
        TrackManager.Track track;
        long endAt;
        final Set<UUID> listeners = new HashSet<>();

        Playback(UUID id) {
            this.id = id;
        }
    }

    private final BoomboxPlugin plugin;
    private final NamespacedKey kItem;
    private final NamespacedKey kBox;
    private final NamespacedKey kDisplay;
    private final NamespacedKey kOwner;
    private final NamespacedKey kVolume;
    private final NamespacedKey kRepeat;
    private final NamespacedKey kAuto;
    private final Map<UUID, Playback> playing = new HashMap<>();
    private BukkitTask task;

    public BoomboxManager(BoomboxPlugin plugin) {
        this.plugin = plugin;
        this.kItem = new NamespacedKey(plugin, "boombox_item");
        this.kBox = new NamespacedKey(plugin, "boombox");
        this.kDisplay = new NamespacedKey(plugin, "display");
        this.kOwner = new NamespacedKey(plugin, "owner");
        this.kVolume = new NamespacedKey(plugin, "volume");
        this.kRepeat = new NamespacedKey(plugin, "repeat");
        this.kAuto = new NamespacedKey(plugin, "autoplay");
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 10L);
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (Playback pb : new ArrayList<>(playing.values())) {
            silence(pb);
        }
        playing.clear();
    }

    // ---------------------------------------------------------------- предмет

    public ItemStack createItem() {
        FileConfiguration c = plugin.getConfig();
        Material mat = Material.matchMaterial(c.getString("item.material", "PAPER"));
        if (mat == null || !mat.isItem()) {
            mat = Material.PAPER;
        }
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plugin.mm(c.getString("item.name", "<gold>Бумбокс")));
        List<Component> lore = new ArrayList<>();
        for (String line : c.getStringList("item.lore")) {
            lore.add(plugin.mm(line));
        }
        meta.lore(lore);
        meta.setCustomModelData(c.getInt("item.custom-model-data", 7001));
        meta.getPersistentDataContainer().set(kItem, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public boolean isBoomboxItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        return item.getItemMeta().getPersistentDataContainer().has(kItem, PersistentDataType.BYTE);
    }

    // ---------------------------------------------------------------- мир

    public boolean isBoombox(Entity e) {
        return e.getPersistentDataContainer().has(kBox, PersistentDataType.BYTE);
    }

    /** Находит хитбокс бумбокса по UUID (только если чанк загружен). */
    public Interaction find(UUID id) {
        Entity e = Bukkit.getEntity(id);
        if (e instanceof Interaction i && i.isValid() && isBoombox(i)) {
            return i;
        }
        return null;
    }

    public boolean canManage(Player p, Interaction box) {
        if (p.hasPermission("boombox.admin")) {
            return true;
        }
        String owner = box.getPersistentDataContainer().get(kOwner, PersistentDataType.STRING);
        return owner != null && owner.equals(p.getUniqueId().toString());
    }

    /** Ставит бумбокс на верхнюю грань блока ground. */
    public boolean place(Player p, Block ground) {
        FileConfiguration cfg = plugin.getConfig();
        Block above = ground.getRelative(BlockFace.UP);
        if (above.isLiquid() || !(above.isEmpty() || above.isPassable())) {
            return false;
        }

        World world = ground.getWorld();
        Location base = ground.getLocation().add(0.5, 1.0, 0.5);
        if (!world.getNearbyEntities(base, 0.4, 0.5, 0.4, en -> isBoombox(en)).isEmpty()) {
            return false;
        }

        float step = (float) Math.max(1.0, cfg.getDouble("model.rotation-step", 45.0));
        float yaw = p.getLocation().getYaw() + 180f + (float) cfg.getDouble("model.yaw-offset", 0.0);
        yaw = Math.round(yaw / step) * step;
        Location spawn = base.clone();
        spawn.setYaw(yaw);
        spawn.setPitch(0f);

        float scale = (float) cfg.getDouble("model.scale", 0.6);
        float width = (float) cfg.getDouble("model.hitbox-width", 0.6);
        float height = (float) cfg.getDouble("model.hitbox-height", 0.5);
        ItemStack modelItem = createItem();
        String owner = p.getUniqueId().toString();

        ItemDisplay display = world.spawn(spawn, ItemDisplay.class, d -> {
            d.setItemStack(modelItem);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            // модель стоит низом на блоке: поднимаем на половину высоты (16px/2 = 0.5 блока)
            d.setTransformation(new Transformation(
                    new Vector3f(0f, 0.5f * scale, 0f),
                    new Quaternionf(),
                    new Vector3f(scale, scale, scale),
                    new Quaternionf()));
            d.setPersistent(true);
            d.getPersistentDataContainer().set(kBox, PersistentDataType.BYTE, (byte) 1);
        });

        world.spawn(base, Interaction.class, i -> {
            i.setInteractionWidth(width);
            i.setInteractionHeight(height);
            i.setPersistent(true);
            i.getPersistentDataContainer().set(kBox, PersistentDataType.BYTE, (byte) 1);
            i.getPersistentDataContainer().set(kOwner, PersistentDataType.STRING, owner);
            i.getPersistentDataContainer().set(kDisplay, PersistentDataType.STRING, display.getUniqueId().toString());
        });

        world.playSound(base, Sound.BLOCK_WOOD_PLACE, 1f, 1f);
        return true;
    }

    /** Забирает бумбокс обратно в инвентарь игрока. */
    public boolean pickup(Player p, Interaction box) {
        if (!canManage(p, box)) {
            p.sendMessage(plugin.msg("not-owner"));
            return false;
        }
        stop(box.getUniqueId());
        removeEntities(box);
        Map<Integer, ItemStack> left = p.getInventory().addItem(createItem());
        for (ItemStack rest : left.values()) {
            p.getWorld().dropItemNaturally(p.getLocation(), rest);
        }
        p.sendMessage(plugin.msg("picked-up"));
        return true;
    }

    private void removeEntities(Interaction box) {
        String ds = box.getPersistentDataContainer().get(kDisplay, PersistentDataType.STRING);
        if (ds != null) {
            try {
                Entity d = Bukkit.getEntity(UUID.fromString(ds));
                if (d != null) {
                    d.remove();
                }
            } catch (IllegalArgumentException ignored) {
                // битый UUID — просто удаляем хитбокс
            }
        }
        box.remove();
    }

    public void openGui(Player p, Interaction box) {
        BoomboxGui gui = new BoomboxGui(plugin, this, box.getUniqueId());
        gui.render();
        p.openInventory(gui.getInventory());
    }

    // ---------------------------------------------------------------- настройки бумбокса (хранятся в самом хитбоксе)

    public int getVolume(Interaction box) {
        Integer v = box.getPersistentDataContainer().get(kVolume, PersistentDataType.INTEGER);
        return v != null ? v : plugin.getConfig().getInt("music.default-volume", 100);
    }

    public void changeVolume(Interaction box, int delta) {
        int v = Math.max(10, Math.min(100, getVolume(box) + delta));
        box.getPersistentDataContainer().set(kVolume, PersistentDataType.INTEGER, v);
        Playback pb = playing.get(box.getUniqueId());
        if (pb != null) {
            play(box, pb.track); // громкость нельзя поменять на лету — перезапускаем трек
        }
    }

    public Repeat getRepeat(Interaction box) {
        String s = box.getPersistentDataContainer().get(kRepeat, PersistentDataType.STRING);
        if (s == null) {
            s = plugin.getConfig().getString("music.default-repeat", "OFF");
        }
        try {
            return Repeat.valueOf(s.toUpperCase());
        } catch (IllegalArgumentException e) {
            return Repeat.OFF;
        }
    }

    public void cycleRepeat(Interaction box) {
        box.getPersistentDataContainer().set(kRepeat, PersistentDataType.STRING, getRepeat(box).next().name());
    }

    // ---------------------------------------------------------------- воспроизведение

    public boolean isPlaying(UUID id) {
        return playing.containsKey(id);
    }

    public TrackManager.Track currentTrack(UUID id) {
        Playback pb = playing.get(id);
        return pb == null ? null : pb.track;
    }

    public void play(Interaction box, TrackManager.Track track) {
        UUID id = box.getUniqueId();
        Playback pb = playing.get(id);
        if (pb != null) {
            silence(pb);
        } else {
            pb = new Playback(id);
            playing.put(id, pb);
        }
        pb.track = track;
        pb.endAt = System.currentTimeMillis() + (long) (track.seconds() * 1000.0) + 300L;
        // запоминаем трек: после рестарта сервера / перезагрузки чанка музыка продолжится
        box.getPersistentDataContainer().set(kAuto, PersistentDataType.STRING, track.id());
        syncListeners(box, pb);
    }

    /** Явная остановка: после рестарта музыка уже не возобновится. */
    public void stop(UUID id) {
        Interaction box = find(id);
        if (box != null) {
            box.getPersistentDataContainer().remove(kAuto);
        }
        Playback pb = playing.get(id);
        if (pb != null) {
            stopInternal(pb);
        }
    }

    /** Останавливает все бумбоксы, играющие этот трек (например, при удалении трека). */
    public void stopTrack(String trackId) {
        for (Playback pb : new ArrayList<>(playing.values())) {
            if (pb.track != null && pb.track.id().equals(trackId)) {
                stop(pb.id);
            }
        }
    }

    private void stopInternal(Playback pb) {
        silence(pb);
        playing.remove(pb.id);
    }

    /** Останавливает звук у всех слушателей и очищает их список. */
    private void silence(Playback pb) {
        if (pb.track != null) {
            String key = pb.track.soundKey();
            for (UUID u : pb.listeners) {
                Player p = Bukkit.getPlayer(u);
                if (p != null && p.isOnline()) {
                    p.stopSound(key, SoundCategory.RECORDS);
                }
            }
        }
        pb.listeners.clear();
    }

    private double radius() {
        return Math.max(4.0, plugin.getConfig().getDouble("music.radius", 24.0));
    }

    /** Включает музыку подошедшим игрокам и выключает ушедшим. */
    private void syncListeners(Interaction box, Playback pb) {
        Location center = box.getLocation().add(0, 0.4, 0);
        World world = center.getWorld();
        double r = radius();
        double rIn = r * r;
        double rOut = (r + 3.0) * (r + 3.0);
        String key = pb.track.soundKey();
        float volume = getVolume(box) / 100f;

        Iterator<UUID> it = pb.listeners.iterator();
        while (it.hasNext()) {
            Player p = Bukkit.getPlayer(it.next());
            boolean online = p != null && p.isOnline();
            if (!online || p.getWorld() != world || p.getLocation().distanceSquared(center) > rOut) {
                if (online) {
                    p.stopSound(key, SoundCategory.RECORDS);
                }
                it.remove();
            }
        }

        for (Player p : world.getPlayers()) {
            if (pb.listeners.contains(p.getUniqueId()) || !plugin.pack().isReady(p)) {
                continue; // уже слушает / ресурспак ещё не загружен
            }
            if (p.getLocation().distanceSquared(center) <= rIn) {
                p.playSound(center, key, SoundCategory.RECORDS, volume, 1f);
                pb.listeners.add(p.getUniqueId());
            }
        }
    }

    /** Возобновляет музыку у бумбоксов, которые играли до рестарта сервера или выгрузки чанка. */
    public void resumeAll() {
        for (World w : Bukkit.getWorlds()) {
            for (Interaction i : w.getEntitiesByClass(Interaction.class)) {
                tryResume(i);
            }
        }
    }

    public void tryResume(Entity e) {
        if (!(e instanceof Interaction box) || !e.isValid() || !isBoombox(box)) {
            return;
        }
        String id = box.getPersistentDataContainer().get(kAuto, PersistentDataType.STRING);
        if (id == null || playing.containsKey(box.getUniqueId())) {
            return;
        }
        TrackManager.Track t = plugin.tracks().get(id);
        if (t == null) {
            if (!plugin.tracks().list().isEmpty()) {
                box.getPersistentDataContainer().remove(kAuto); // трек удалён
            }
            return;
        }
        play(box, t);
    }

    private TrackManager.Track nextTrack(TrackManager.Track current) {
        List<TrackManager.Track> all = plugin.tracks().list();
        if (all.isEmpty()) {
            return null;
        }
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id().equals(current.id())) {
                return all.get((i + 1) % all.size());
            }
        }
        return all.get(0);
    }

    private void tick() {
        if (playing.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Playback pb : new ArrayList<>(playing.values())) {
            Interaction box = find(pb.id);
            if (box == null) {
                stopInternal(pb); // бумбокс удалён или чанк выгружен
                continue;
            }
            if (now >= pb.endAt) {
                TrackManager.Track next = null;
                switch (getRepeat(box)) {
                    case ONE -> next = pb.track;
                    case ALL -> next = nextTrack(pb.track);
                    case OFF -> next = null;
                }
                if (next == null) {
                    box.getPersistentDataContainer().remove(kAuto); // трек закончился сам — не возобновлять
                    stopInternal(pb);
                } else {
                    play(box, next);
                }
                continue;
            }
            syncListeners(box, pb);
        }
    }
}
