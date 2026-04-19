package epicc.dev.service.pipeline;

import org.bukkit.entity.Player;

public interface HudPipeline {
    String mode();

    void showHud(Player player);

    void hideHud(Player player);

    void removeHud(Player player);

    void updateHud(Player player);

    void shutdown();
}
