package epicc.dev.task;

import epicc.dev.MiniMap;
import epicc.dev.service.HudService;
import epicc.dev.service.SessionService;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

public final class TickUpdateTask {
    private final MiniMap plugin;
    private final SessionService sessionService;
    private final HudService hudService;
    private BukkitTask task;

    public TickUpdateTask(MiniMap plugin, SessionService sessionService, HudService hudService) {
        this.plugin = plugin;
        this.sessionService = sessionService;
        this.hudService = hudService;
    }

    public void start() {
        stop();

        long interval = Math.max(1L, this.plugin.getConfig().getLong("hud.updateIntervalTicks", 2L));
        this.task = this.plugin.getServer().getScheduler().runTaskTimer(this.plugin, () -> {
            for (Player player : this.plugin.getServer().getOnlinePlayers()) {
                if (this.sessionService.isPackLoaded(player.getUniqueId()) && this.sessionService.isHudEnabled(player.getUniqueId())) {
                    this.hudService.updateHud(player);
                }
            }
        }, interval, interval);
    }

    public void restart() {
        start();
    }

    public void stop() {
        if (this.task != null) {
            this.task.cancel();
            this.task = null;
        }
    }
}
