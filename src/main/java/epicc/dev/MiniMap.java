package epicc.dev;

import com.github.retrooper.packetevents.PacketEvents;
import epicc.dev.commands.MinimapCommand;
import epicc.dev.listeners.PlayerListener;
import epicc.dev.service.HudService;
import epicc.dev.service.PackBuildService;
import epicc.dev.service.PackDeliveryService;
import epicc.dev.service.PackHttpService;
import epicc.dev.service.SessionService;
import epicc.dev.task.TickUpdateTask;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class MiniMap extends JavaPlugin {
    private SessionService sessionService;
    private PackBuildService packBuildService;
    private PackHttpService packHttpService;
    private PackDeliveryService packDeliveryService;
    private HudService hudService;
    private TickUpdateTask tickUpdateTask;
    private boolean packetEventsLoaded;
    private boolean packetEventsInitialized;
    private Throwable packetEventsFailure;

    @Override
    public void onLoad() {
        try {
            PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this));
            PacketEvents.getAPI().getSettings()
                    .reEncodeByDefault(false)
                    .checkForUpdates(false)
                    .debug(false);
            PacketEvents.getAPI().load();
            this.packetEventsLoaded = true;
            getLogger().info("PacketEvents loaded.");
        } catch (Throwable throwable) {
            this.packetEventsLoaded = false;
            this.packetEventsFailure = throwable;
            getLogger().warning("PacketEvents failed to load during onLoad: " + throwable.getMessage());
        }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();

        boolean modernMode = "modern".equalsIgnoreCase(getConfig().getString("hud.pipeline.mode", "legacy"));
        if (this.packetEventsLoaded) {
            try {
                PacketEvents.getAPI().init();
                this.packetEventsInitialized = true;
                getLogger().info("PacketEvents initialized.");
            } catch (Throwable throwable) {
                this.packetEventsInitialized = false;
                this.packetEventsFailure = throwable;
                if (modernMode) {
                    throw new IllegalStateException("Failed to initialize PacketEvents in modern mode", throwable);
                }
                getLogger().warning("PacketEvents init failed. Legacy mode can continue: " + throwable.getMessage());
            }
        } else if (modernMode) {
            throw new IllegalStateException("Modern mode requires PacketEvents, but packet layer failed to load.", this.packetEventsFailure);
        }

        this.sessionService = new SessionService();
        this.packBuildService = new PackBuildService(this);
        this.packHttpService = new PackHttpService(this, this.packBuildService);
        this.hudService = new HudService(this, this.sessionService);
        this.packDeliveryService = new PackDeliveryService(this, this.packBuildService, this.packHttpService, this.sessionService, this.hudService);
        this.tickUpdateTask = new TickUpdateTask(this, this.sessionService, this.hudService);

        this.packBuildService.rebuildPack();
        this.packHttpService.start();

        getServer().getPluginManager().registerEvents(
                new PlayerListener(this, this.packDeliveryService, this.sessionService, this.hudService),
                this
        );

        MinimapCommand minimapCommand = new MinimapCommand(this, this.packBuildService, this.packDeliveryService, this.sessionService, this.hudService);
        PluginCommand command = getCommand("minimap");
        if (command != null) {
            command.setExecutor(minimapCommand);
            command.setTabCompleter(minimapCommand);
        }

        this.tickUpdateTask.start();

        getServer().getScheduler().runTaskLater(this, () -> {
            for (Player player : getServer().getOnlinePlayers()) {
                this.packDeliveryService.sendPack(player);
            }
        }, 40L);

        getLogger().info("MiniMap has been enabled! (pipeline=" + this.hudService.currentMode() + ")");
    }

    @Override
    public void onDisable() {
        if (this.tickUpdateTask != null) {
            this.tickUpdateTask.stop();
        }

        if (this.hudService != null) {
            this.hudService.shutdown();
        }

        if (this.packHttpService != null) {
            this.packHttpService.stop();
        }

        if (this.sessionService != null) {
            this.sessionService.clear();
        }

        if (this.packetEventsInitialized) {
            try {
                PacketEvents.getAPI().terminate();
            } catch (Throwable throwable) {
                getLogger().warning("PacketEvents terminate failed: " + throwable.getMessage());
            } finally {
                this.packetEventsInitialized = false;
            }
        }

        getLogger().info("MiniMap has been disabled!");
    }

    public void reloadRuntime() {
        reloadConfig();
        this.hudService.reloadPipeline();

        this.packBuildService.rebuildPack();
        this.packHttpService.restart();
        this.tickUpdateTask.restart();

        for (Player player : getServer().getOnlinePlayers()) {
            this.packDeliveryService.sendPack(player);
        }
    }

    public boolean isPacketEventsReady() {
        return this.packetEventsLoaded && this.packetEventsInitialized;
    }
}
