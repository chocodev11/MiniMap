package epicc.dev.service;

import epicc.dev.MiniMap;
import java.util.UUID;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;

public final class PackDeliveryService {
    private final MiniMap plugin;
    private final PackBuildService packBuildService;
    private final PackHttpService packHttpService;
    private final SessionService sessionService;
    private final HudService hudService;

    public PackDeliveryService(
            MiniMap plugin,
            PackBuildService packBuildService,
            PackHttpService packHttpService,
            SessionService sessionService,
            HudService hudService
    ) {
        this.plugin = plugin;
        this.packBuildService = packBuildService;
        this.packHttpService = packHttpService;
        this.sessionService = sessionService;
        this.hudService = hudService;
    }

    public void sendPackToAll() {
        for (Player player : this.plugin.getServer().getOnlinePlayers()) {
            sendPack(player);
        }
    }

    public void sendPack(Player player) {
        if (!player.hasPermission("minimap.hud")) {
            return;
        }

        UUID playerId = player.getUniqueId();
        PackBuildService.BuildArtifact artifact = this.packBuildService.getCurrentArtifact();
        UUID packId = artifact.id();
        UUID lastRequestedPackId = this.sessionService.getLastRequestedPackId(playerId);
        if (packId.equals(lastRequestedPackId)
                && (this.sessionService.isPackLoaded(playerId) || this.sessionService.isPackRequestPending(playerId))) {
            return;
        }

        String url = this.packHttpService.getPackUrl();

        FileConfiguration config = this.plugin.getConfig();
        boolean force = config.getBoolean("pack.host.force", true);
        String prompt = config.getString("pack.host.prompt", "MiniMap PoC resource pack");
        if (prompt != null && prompt.isBlank()) {
            prompt = null;
        }

        this.sessionService.setPackLoaded(playerId, false);
        this.sessionService.setPackRequestPending(playerId, true);
        this.sessionService.setLastRequestedPackId(playerId, packId);
        this.hudService.hideHud(player);

        player.addResourcePack(packId, url, artifact.sha1(), prompt, force);
        this.plugin.getLogger().info("Sent minimap resource pack to " + player.getName() + " (" + url + ")");
    }

    public void handleResourcePackStatus(PlayerResourcePackStatusEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();

        switch (event.getStatus()) {
            case SUCCESSFULLY_LOADED -> {
                this.sessionService.setPackLoaded(playerId, true);
                this.sessionService.setPackRequestPending(playerId, false);
                this.hudService.showHud(player);
                this.plugin.getLogger().info("Resource pack loaded for " + player.getName());
            }
            case DECLINED, DISCARDED, FAILED_DOWNLOAD, FAILED_RELOAD, INVALID_URL -> {
                this.sessionService.setPackLoaded(playerId, false);
                this.sessionService.setPackRequestPending(playerId, false);
                this.hudService.hideHud(player);

                if (event.getStatus() == PlayerResourcePackStatusEvent.Status.FAILED_RELOAD) {
                    this.plugin.getLogger().warning("Pack reload failed for " + player.getName() + ". Shader mods (OptiFine/Iris) may conflict with this minimap.");
                } else {
                    this.plugin.getLogger().warning("Resource pack status for " + player.getName() + ": " + event.getStatus());
                }
            }
            case ACCEPTED, DOWNLOADED -> {
                this.sessionService.setPackRequestPending(playerId, true);
                this.plugin.getLogger().info("Resource pack status for " + player.getName() + ": " + event.getStatus());
            }
        }
    }
}
