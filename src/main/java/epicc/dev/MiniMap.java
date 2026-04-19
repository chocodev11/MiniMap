package epicc.dev;

import epicc.dev.commands.MinimapCommand;
import epicc.dev.listeners.PlayerListener;
import epicc.dev.service.HudService;
import epicc.dev.service.PackBuildService;
import epicc.dev.service.PackDeliveryService;
import epicc.dev.service.PackHttpService;
import epicc.dev.service.SessionService;
import epicc.dev.task.TickUpdateTask;
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

    @Override
    public void onEnable() {
        saveDefaultConfig();

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

        getLogger().info("MiniMap has been enabled!");
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

        getLogger().info("MiniMap has been disabled!");
    }

    public void reloadRuntime() {
        reloadConfig();

        this.packBuildService.rebuildPack();
        this.packHttpService.restart();
        this.tickUpdateTask.restart();

        for (Player player : getServer().getOnlinePlayers()) {
            this.packDeliveryService.sendPack(player);
        }
    }
}
