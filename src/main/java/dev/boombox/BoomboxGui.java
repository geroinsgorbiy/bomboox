package dev.boombox;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Меню бумбокса: список треков (5 рядов) + панель управления (нижний ряд). */
public final class BoomboxGui implements InventoryHolder {

    private static final int PER_PAGE = 45;

    private final BoomboxPlugin plugin;
    private final BoomboxManager mgr;
    private final UUID boxId;
    private final Inventory inv;
    private int page = 0;

    public BoomboxGui(BoomboxPlugin plugin, BoomboxManager mgr, UUID boxId) {
        this.plugin = plugin;
        this.mgr = mgr;
        this.boxId = boxId;
        this.inv = Bukkit.createInventory(this, 54, plugin.msg("gui-title"));
    }

    @Override
    public Inventory getInventory() {
        return inv;
    }

    public void render() {
        inv.clear();
        List<TrackManager.Track> list = plugin.tracks().list();
        int pages = Math.max(1, (list.size() + PER_PAGE - 1) / PER_PAGE);
        page = Math.max(0, Math.min(page, pages - 1));

        Interaction box = mgr.find(boxId);
        TrackManager.Track current = mgr.currentTrack(boxId);

        if (list.isEmpty()) {
            inv.setItem(22, icon(Material.PAPER, plugin.msg("gui-empty"), List.of(plugin.msg("gui-empty-lore"))));
        }
        for (int i = 0; i < PER_PAGE; i++) {
            int idx = page * PER_PAGE + i;
            if (idx >= list.size()) {
                break;
            }
            TrackManager.Track t = list.get(idx);
            boolean active = current != null && current.id().equals(t.id());
            List<Component> lore = new ArrayList<>();
            lore.add(plugin.msg("gui-track-time", "time", TrackManager.fmt(t.seconds())));
            lore.add(active ? plugin.msg("gui-track-active") : plugin.msg("gui-track-click"));
            inv.setItem(i, icon(active ? Material.MUSIC_DISC_PIGSTEP : Material.MUSIC_DISC_13,
                    Component.text(t.name()).decoration(TextDecoration.ITALIC, false), lore));
        }

        ItemStack filler = icon(Material.GRAY_STAINED_GLASS_PANE, Component.space(), null);
        for (int s = 45; s < 54; s++) {
            inv.setItem(s, filler);
        }

        int volume = box == null ? 100 : mgr.getVolume(box);
        BoomboxManager.Repeat repeat = box == null ? BoomboxManager.Repeat.OFF : mgr.getRepeat(box);

        if (page > 0) {
            inv.setItem(45, icon(Material.ARROW, plugin.msg("gui-prev"), null));
        }
        if (page < pages - 1) {
            inv.setItem(53, icon(Material.ARROW, plugin.msg("gui-next"), null));
        }
        inv.setItem(47, icon(Material.BARRIER, plugin.msg("gui-stop"), null));
        inv.setItem(48, icon(Material.RED_DYE, plugin.msg("gui-vol-down"), null));
        inv.setItem(50, icon(Material.LIME_DYE, plugin.msg("gui-vol-up"), null));

        List<Component> info = new ArrayList<>();
        info.add(plugin.msg("gui-volume", "volume", String.valueOf(volume)));
        inv.setItem(49, icon(Material.NOTE_BLOCK,
                current != null ? plugin.msg("gui-now", "name", current.name()) : plugin.msg("gui-idle"), info));

        String repeatKey = switch (repeat) {
            case OFF -> "gui-repeat-off";
            case ONE -> "gui-repeat-one";
            case ALL -> "gui-repeat-all";
        };
        inv.setItem(51, icon(Material.REPEATER, plugin.msg(repeatKey), null));
        inv.setItem(52, icon(Material.HOPPER, plugin.msg("gui-pickup"), null));
    }

    /** Обработка клика по верхнему инвентарю. */
    public void click(Player p, int slot) {
        Interaction box = mgr.find(boxId);
        if (box == null || !box.getWorld().equals(p.getWorld())
                || box.getLocation().distanceSquared(p.getLocation()) > 100.0) {
            p.closeInventory();
            return;
        }

        List<TrackManager.Track> list = plugin.tracks().list();
        if (slot >= 0 && slot < PER_PAGE) {
            int idx = page * PER_PAGE + slot;
            if (idx < list.size()) {
                mgr.play(box, list.get(idx));
            }
        } else {
            switch (slot) {
                case 45 -> page--;
                case 53 -> page++;
                case 47 -> mgr.stop(boxId);
                case 48 -> mgr.changeVolume(box, -10);
                case 50 -> mgr.changeVolume(box, 10);
                case 51 -> mgr.cycleRepeat(box);
                case 52 -> {
                    mgr.pickup(p, box);
                    p.closeInventory();
                    return;
                }
                default -> {
                }
            }
        }
        render();
    }

    private ItemStack icon(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name);
        if (lore != null && !lore.isEmpty()) {
            meta.lore(lore);
        }
        meta.addItemFlags(ItemFlag.values());
        item.setItemMeta(meta);
        return item;
    }
}
