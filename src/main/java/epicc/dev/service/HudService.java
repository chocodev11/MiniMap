package epicc.dev.service;

import epicc.dev.MiniMap;
import epicc.dev.service.pipeline.HudPipeline;
import epicc.dev.service.pipeline.LegacyHudPipeline;
import epicc.dev.service.pipeline.ModernHudPipeline;
import org.bukkit.entity.Player;

public final class HudService {
    private final MiniMap plugin;
    private final SessionService sessionService;
    private HudPipeline pipeline;

    public HudService(MiniMap plugin, SessionService sessionService) {
        this.plugin = plugin;
        this.sessionService = sessionService;
        this.pipeline = createPipeline();
    }

    public void showHud(Player player) {
        this.pipeline.showHud(player);
    }

    public void hideHud(Player player) {
        this.pipeline.hideHud(player);
    }

    public void removeHud(Player player) {
        this.pipeline.removeHud(player);
    }

    public void updateHud(Player player) {
        this.pipeline.updateHud(player);
    }

    public void reloadPipeline() {
        String wantedMode = resolvePipelineMode();
        if (this.pipeline.mode().equals(wantedMode)) {
            return;
        }

        this.pipeline.shutdown();
        this.pipeline = createPipeline();
        this.plugin.getLogger().info("HUD pipeline switched to " + this.pipeline.mode());
    }

    public String currentMode() {
        return this.pipeline.mode();
    }

    public void shutdown() {
        this.pipeline.shutdown();
    }

    private HudPipeline createPipeline() {
        String mode = resolvePipelineMode();
        if ("modern".equals(mode)) {
            if (!this.plugin.isPacketEventsReady()) {
                throw new IllegalStateException("Modern HUD pipeline requires PacketEvents to be initialized.");
            }
            this.plugin.getLogger().info("HUD pipeline initialized: modern");
            return new ModernHudPipeline(this.plugin, this.sessionService);
        }

        this.plugin.getLogger().info("HUD pipeline initialized: legacy");
        return new LegacyHudPipeline(this.plugin, this.sessionService);
    }

    private String resolvePipelineMode() {
        String configured = this.plugin.getConfig().getString("hud.pipeline.mode", "legacy");
        if ("modern".equalsIgnoreCase(configured)) {
            return "modern";
        }
        return "legacy";
    }
}
