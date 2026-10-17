package dev.boombox;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class BoomboxListener implements Listener {

    private final BoomboxPlugin plugin;

    public BoomboxListener(BoomboxPlugin plugin) {
        this.plugin = plugin;
    }

    /** ПКМ предметом-бумбоксом по верху блока — ставим. */
    @EventHandler(ignoreCancelled = true)
    public void onUseItem(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || e.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        BoomboxManager mgr = plugin.boomboxes();
        if (!mgr.isBoomboxItem(e.getItem())) {
            return;
        }
        Block clicked = e.getClickedBlock();
        Player p = e.getPlayer();
        if (clicked == null) {
            return;
        }
        // ящик/дверь/кнопка и т.п. — пусть сработает обычное взаимодействие (Shift — ставить всё равно)
        if (clicked.getType().isInteractable() && !p.isSneaking()) {
            return;
        }
        e.setCancelled(true);

        if (!p.hasPermission("boombox.place")) {
            p.sendMessage(plugin.msg("no-permission"));
            return;
        }
        if (e.getBlockFace() != BlockFace.UP) {
            p.sendMessage(plugin.msg("need-top"));
            return;
        }
        if (!mgr.place(p, clicked)) {
            p.sendMessage(plugin.msg("cannot-place"));
            return;
        }

        if (p.getGameMode() != GameMode.CREATIVE) {
            ItemStack hand = p.getInventory().getItemInMainHand();
            if (hand.getAmount() <= 1) {
                p.getInventory().setItemInMainHand(null);
            } else {
                hand.setAmount(hand.getAmount() - 1);
                p.getInventory().setItemInMainHand(hand);
            }
        }
        p.swingMainHand();
        p.sendMessage(plugin.msg("placed"));
    }

    /**
     * ПКМ по бумбоксу — меню; Shift+ПКМ владельцем/админом — забрать.
     * Обрабатываем даже если другой плагин (защита лобби) отменил событие: это наша собственная сущность.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Entity clicked = e.getRightClicked();
        if (!(clicked instanceof Interaction box) || !plugin.boomboxes().isBoombox(box)) {
            return;
        }
        e.setCancelled(true);

        Player p = e.getPlayer();
        if (!p.hasPermission("boombox.use")) {
            p.sendMessage(plugin.msg("no-permission"));
            return;
        }
        if (p.isSneaking() && plugin.boomboxes().canManage(p, box)) {
            plugin.boomboxes().pickup(p, box);
            return;
        }
        plugin.boomboxes().openGui(p, box);
    }

    @EventHandler
    public void onGuiClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof BoomboxGui gui)) {
            return;
        }
        e.setCancelled(true);
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getView().getTopInventory()) {
            return;
        }
        if (e.getWhoClicked() instanceof Player p) {
            gui.click(p, e.getRawSlot());
        }
    }

    @EventHandler
    public void onGuiDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof BoomboxGui) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (!plugin.getConfig().getBoolean("resource-pack.send-on-join", true)) {
            return;
        }
        Player p = e.getPlayer();
        long delay = plugin.getConfig().getLong("resource-pack.join-delay-ticks", 40L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline()) {
                plugin.pack().sendTo(p);
            }
        }, delay);
    }

    /** Отслеживаем, у кого ресурспак реально загружен — только таким игрокам включаем музыку. */
    @EventHandler
    public void onPackStatus(PlayerResourcePackStatusEvent e) {
        String status = e.getStatus().name();
        UUID id = e.getPlayer().getUniqueId();
        if (status.equals("SUCCESSFULLY_LOADED")) {
            plugin.pack().markLoaded(id);
        } else if (status.equals("DECLINED") || status.equals("DISCARDED")
                || status.equals("INVALID_URL") || status.startsWith("FAILED")) {
            plugin.pack().markUnloaded(id);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        plugin.pack().markUnloaded(e.getPlayer().getUniqueId());
    }

    /** Загрузился чанк с бумбоксом, который играл до выгрузки/рестарта — продолжаем. */
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        List<Entity> boxes = new ArrayList<>();
        for (Entity en : e.getEntities()) {
            if (en instanceof Interaction i && plugin.boomboxes().isBoombox(i)) {
                boxes.add(en);
            }
        }
        if (boxes.isEmpty()) {
            return;
        }
        // на следующем тике: сущности к этому моменту точно зарегистрированы в мире
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Entity en : boxes) {
                plugin.boomboxes().tryResume(en);
            }
        });
    }
}
