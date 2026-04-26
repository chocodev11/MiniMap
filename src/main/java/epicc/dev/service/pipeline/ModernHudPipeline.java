package epicc.dev.service.pipeline;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBossBar;
import epicc.dev.MiniMap;
import epicc.dev.service.SessionService;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Location;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

public final class ModernHudPipeline implements HudPipeline {
    private static final String LEGACY_PREFIX = "\u00A7";
    private static final double MODERN_MAX_PAN_PIXELS = 63.5D;
    private static final LegacyComponentSerializer LEGACY_SERIALIZER = LegacyComponentSerializer.legacySection();

    private final MiniMap plugin;
    private final SessionService sessionService;
    private final Map<UUID, UUID> activeBars = new ConcurrentHashMap<>();

    public ModernHudPipeline(MiniMap plugin, SessionService sessionService) {
        this.plugin = plugin;
        this.sessionService = sessionService;
    }

    @Override
    public String mode() {
        return "modern";
    }

    @Override
    public void showHud(Player player) {
        updateHud(player);
    }

    @Override
    public void hideHud(Player player) {
        UUID playerId = player.getUniqueId();
        UUID barId = this.activeBars.remove(playerId);
        if (barId != null) {
            WrapperPlayServerBossBar removePacket = new WrapperPlayServerBossBar(barId, WrapperPlayServerBossBar.Action.REMOVE);
            PacketEvents.getAPI().getPlayerManager().sendPacket(player, removePacket);
        }
        this.sessionService.clearSmoothedYaw(playerId);
        this.sessionService.clearLastHudTitle(playerId);
    }

    @Override
    public void removeHud(Player player) {
        hideHud(player);
    }

    @Override
    public void updateHud(Player player) {
        UUID playerId = player.getUniqueId();
        if (!this.sessionService.isHudEnabled(playerId)
                || !this.sessionService.isPackLoaded(playerId)
                || !player.hasPermission("minimap.hud")) {
            hideHud(player);
            return;
        }

        String title = buildHudTitle(player);
        String lastTitle = this.sessionService.getLastHudTitle(playerId);

        UUID barId = this.activeBars.get(playerId);
        if (barId == null) {
            barId = barIdFor(playerId);
            this.activeBars.put(playerId, barId);
            sendAdd(player, barId, title);
            this.sessionService.setLastHudTitle(playerId, title);
            return;
        }

        if (!title.equals(lastTitle)) {
            sendTitleUpdate(player, barId, title);
            this.sessionService.setLastHudTitle(playerId, title);
        }
    }

    @Override
    public void shutdown() {
        for (Player player : this.plugin.getServer().getOnlinePlayers()) {
            hideHud(player);
        }
        this.activeBars.clear();
    }

    private void sendAdd(Player player, UUID barId, String title) {
        WrapperPlayServerBossBar addPacket = new WrapperPlayServerBossBar(barId, WrapperPlayServerBossBar.Action.ADD);
        addPacket.setTitle(legacyText(title));
        addPacket.setHealth(1.0F);
        addPacket.setColor(BossBar.Color.WHITE);
        addPacket.setOverlay(BossBar.Overlay.PROGRESS);
        addPacket.setFlags(EnumSet.noneOf(BossBar.Flag.class));
        PacketEvents.getAPI().getPlayerManager().sendPacket(player, addPacket);
    }

    private void sendTitleUpdate(Player player, UUID barId, String title) {
        WrapperPlayServerBossBar packet = new WrapperPlayServerBossBar(barId, WrapperPlayServerBossBar.Action.UPDATE_TITLE);
        packet.setTitle(legacyText(title));
        PacketEvents.getAPI().getPlayerManager().sendPacket(player, packet);
    }

    private Component legacyText(String input) {
        return LEGACY_SERIALIZER.deserialize(input);
    }

    private static UUID barIdFor(UUID playerId) {
        return UUID.nameUUIDFromBytes(("minimap-modern-bossbar:" + playerId).getBytes(StandardCharsets.UTF_8));
    }

    private String buildHudTitle(Player player) {
        FileConfiguration config = this.plugin.getConfig();
        String layout = config.getString("hud.layout", "{nw}{ne}{sw}{se}{border}{marker}");
        UUID playerId = player.getUniqueId();

        String nw = decodeUnicodeEscapes(config.getString("hud.glyphs.nw", "\\uE101"));
        String ne = decodeUnicodeEscapes(config.getString("hud.glyphs.ne", "\\uE102"));
        String sw = decodeUnicodeEscapes(config.getString("hud.glyphs.sw", "\\uE103"));
        String se = decodeUnicodeEscapes(config.getString("hud.glyphs.se", "\\uE104"));
        String border = decodeUnicodeEscapes(config.getString("hud.glyphs.border", "\\uE105"));
        String marker = decodeUnicodeEscapes(config.getString("hud.glyphs.marker", "\\uE106"));

        Location location = player.getLocation();
        double centerX = config.getDouble("hud.map.centerX", 0.0D);
        double centerZ = config.getDouble("hud.map.centerZ", 0.0D);
        double radius = Math.max(1.0D, config.getDouble("hud.map.radiusBlocks", 256.0D));

        double panXBlocks = centerX - location.getX();
        double panZBlocks = centerZ - location.getZ();
        if (config.getBoolean("hud.map.pan.invert", false)) {
            panXBlocks = -panXBlocks;
            panZBlocks = -panZBlocks;
        }
        double panClamp = Math.min(radius, MODERN_MAX_PAN_PIXELS);
        double panXPixels = clamp(panXBlocks, -panClamp, panClamp);
        double panZPixels = clamp(panZBlocks, -panClamp, panClamp);

        boolean invertYaw = config.getBoolean("hud.map.rotation.invertYaw", false);
        double yawOffsetDegrees = config.getDouble("hud.map.rotation.offsetDegrees", 180.0D);
        boolean yawSmoothingEnabled = config.getBoolean("hud.map.rotation.smoothing.enabled", false);
        double yawSmoothingAlpha = clamp(config.getDouble("hud.map.rotation.smoothing.alpha", 1.0D), 0.0D, 1.0D);
        double playerYawDegrees = normalizeUnsignedDegrees(location.getYaw());
        double rawMapRotationDegrees = normalizeUnsignedDegrees((invertYaw ? -playerYawDegrees : playerYawDegrees) + yawOffsetDegrees);
        double mapRotationDegrees = rawMapRotationDegrees;
        if (yawSmoothingEnabled && yawSmoothingAlpha > 0.0D && yawSmoothingAlpha < 1.0D) {
            mapRotationDegrees = this.sessionService.smoothYawDegrees(playerId, rawMapRotationDegrees, yawSmoothingAlpha);
        } else {
            this.sessionService.clearSmoothedYaw(playerId);
        }

        int yaw8 = encodeUnsigned8FromDegrees(mapRotationDegrees);
        int panX7 = encodeUnsigned7FromPixels(panXPixels);
        int panY7 = encodeUnsigned7FromPixels(panZPixels);
        String payloadColor = modernColor(yaw8, panX7, panY7);

        int sideBit = "right".equalsIgnoreCase(config.getString("hud.map.leftOrRight", "left")) ? 1 : 0;
        String leftOrRight = sideBit == 1 ? "right" : "left";

        return layout
                .replace("{nw}", payloadColor + nw)
                .replace("{ne}", payloadColor + ne)
                .replace("{sw}", payloadColor + sw)
                .replace("{se}", payloadColor + se)
                .replace("{border}", payloadColor + border)
                .replace("{marker}", payloadColor + marker)
                .replace("{side}", leftOrRight)
                + LEGACY_PREFIX + "r";
    }

    private static int encodeUnsigned7FromPixels(double value) {
        int payload = (int) Math.round(value + MODERN_MAX_PAN_PIXELS);
        return (int) clamp(payload, 0, 127);
    }

    private static int encodeUnsigned8FromDegrees(double degrees) {
        int payload = (int) Math.round((normalizeUnsignedDegrees(degrees) / 360.0D) * 255.0D);
        return (int) clamp(payload, 0, 255);
    }

    private static double normalizeUnsignedDegrees(double degrees) {
        double normalized = degrees % 360.0D;
        if (normalized < 0.0D) {
            normalized += 360.0D;
        }
        return normalized;
    }

    private static String modernColor(int yaw8, int panX7, int panY7) {
        int red = 0x80 | (panX7 & 0x7F);
        int green = 0x80 | (panY7 & 0x7F);
        int blue = yaw8 & 0xFF;
        return hexColor(red, green, blue);
    }

    private static String hexColor(int red, int green, int blue) {
        String hex = String.format("%02X%02X%02X", red, green, blue);
        return LEGACY_PREFIX + "x"
                + LEGACY_PREFIX + hex.charAt(0)
                + LEGACY_PREFIX + hex.charAt(1)
                + LEGACY_PREFIX + hex.charAt(2)
                + LEGACY_PREFIX + hex.charAt(3)
                + LEGACY_PREFIX + hex.charAt(4)
                + LEGACY_PREFIX + hex.charAt(5);
    }

    private static String decodeUnicodeEscapes(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }

        StringBuilder output = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char current = input.charAt(i);
            if (current == '\\' && i + 5 < input.length() && input.charAt(i + 1) == 'u') {
                String hex = input.substring(i + 2, i + 6);
                try {
                    output.append((char) Integer.parseInt(hex, 16));
                    i += 5;
                    continue;
                } catch (NumberFormatException ignored) {
                }
            }

            output.append(current);
        }

        return output.toString();
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
