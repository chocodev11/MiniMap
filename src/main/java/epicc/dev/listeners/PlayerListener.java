package epicc.dev.listeners;

import epicc.dev.MiniMap;
import epicc.dev.service.HudService;
import epicc.dev.service.PackDeliveryService;
import epicc.dev.service.SessionService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;

public final class PlayerListener implements Listener {
    private final MiniMap plugin;
    private final PackDeliveryService packDeliveryService;
    private final SessionService sessionService;
    private final HudService hudService;

    public PlayerListener(MiniMap plugin, PackDeliveryService packDeliveryService, SessionService sessionService, HudService hudService) {
        this.plugin = plugin;
        this.packDeliveryService = packDeliveryService;
        this.sessionService = sessionService;
        this.hudService = hudService;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        this.sessionService.ensure(player.getUniqueId());
        this.sessionService.setPackLoaded(player.getUniqueId(), false);

        if (!player.hasPermission("minimap.hud")) {
            return;
        }

        this.plugin.getServer().getScheduler().runTaskLater(this.plugin, () -> this.packDeliveryService.sendPack(player), 40L);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        this.hudService.removeHud(player);
        this.sessionService.remove(player.getUniqueId());
    }

    @EventHandler
    public void onPlayerResourcePackStatus(PlayerResourcePackStatusEvent event) {
        this.packDeliveryService.handleResourcePackStatus(event);
    }
}
