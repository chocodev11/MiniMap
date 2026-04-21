package epicc.dev.service.pipeline;

import epicc.dev.MiniMap;
import epicc.dev.service.SessionService;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

public final class LegacyHudPipeline implements HudPipeline {
    private static final String LEGACY_PREFIX = "\u00A7";
    private static final double LEGACY_MAX_PAN_PIXELS = 31.5D;

    private final MiniMap plugin;
    private final SessionService sessionService;
    private final Map<UUID, BossBar> bars = new ConcurrentHashMap<>();

    public LegacyHudPipeline(MiniMap plugin, SessionService sessionService) {
        this.plugin = plugin;
        this.sessionService = sessionService;
    }

    @Override
    public String mode() {
        return "legacy";
    }

    @Override
    public void showHud(Player player) {
        UUID playerId = player.getUniqueId();
        if (!this.sessionService.isHudEnabled(playerId) || !this.sessionService.isPackLoaded(playerId)) {
            return;
        }

        BossBar bar = this.bars.computeIfAbsent(playerId,
                ignored -> Bukkit.createBossBar("", BarColor.WHITE, BarStyle.SOLID));
        if (!bar.getPlayers().contains(player)) {
            bar.addPlayer(player);
        }

        if (!bar.isVisible()) {
            bar.setVisible(true);
        }
        if (bar.getProgress() != 1.0D) {
            bar.setProgress(1.0D);
        }

        String title = buildHudTitle(player);
        String lastTitle = this.sessionService.getLastHudTitle(playerId);
        if (!title.equals(lastTitle)) {
            bar.setTitle(title);
            this.sessionService.setLastHudTitle(playerId, title);
        }
    }

    @Override
    public void hideHud(Player player) {
        UUID playerId = player.getUniqueId();
        BossBar bar = this.bars.get(playerId);
        if (bar != null) {
            bar.removePlayer(player);
        }
        this.sessionService.clearLastHudTitle(playerId);
    }

    @Override
    public void removeHud(Player player) {
        UUID playerId = player.getUniqueId();
        BossBar bar = this.bars.remove(playerId);
        if (bar != null) {
            bar.removeAll();
        }
        this.sessionService.clearLastHudTitle(playerId);
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

        BossBar bar = this.bars.computeIfAbsent(playerId,
                ignored -> Bukkit.createBossBar("", BarColor.WHITE, BarStyle.SOLID));
        if (!bar.getPlayers().contains(player)) {
            bar.addPlayer(player);
        }

        if (!bar.isVisible()) {
            bar.setVisible(true);
        }
        if (bar.getProgress() != 1.0D) {
            bar.setProgress(1.0D);
        }

        String title = buildHudTitle(player);
        String lastTitle = this.sessionService.getLastHudTitle(playerId);
        if (!title.equals(lastTitle)) {
            bar.setTitle(title);
            this.sessionService.setLastHudTitle(playerId, title);
        }
    }

    @Override
    public void shutdown() {
        for (BossBar bar : this.bars.values()) {
            bar.removeAll();
        }

        this.bars.clear();
    }

    private String buildHudTitle(Player player) {
        FileConfiguration config = this.plugin.getConfig();
        String layout = config.getString("hud.layout", "{nw}{ne}{sw}{se}{border}{marker}");

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
        double panClamp = Math.min(radius, LEGACY_MAX_PAN_PIXELS);
        double panXPixels = clamp(panXBlocks, -panClamp, panClamp);
        double panZPixels = clamp(panZBlocks, -panClamp, panClamp);

        boolean invertYaw = config.getBoolean("hud.map.rotation.invertYaw", false);
        double yawOffsetDegrees = config.getDouble("hud.map.rotation.offsetDegrees", 180.0D);
        double playerYawDegrees = normalizeUnsignedDegrees(location.getYaw());
        double mapRotationDegrees = normalizeUnsignedDegrees((invertYaw ? -playerYawDegrees : playerYawDegrees) + yawOffsetDegrees);

        int yaw6 = encodeUnsigned6FromDegrees(mapRotationDegrees);
        int markerX6 = encodeUnsigned6FromPixels(panXPixels);
        int markerY6 = encodeUnsigned6FromPixels(panZPixels);
        int sideBit = "right".equalsIgnoreCase(config.getString("hud.map.leftOrRight", "left")) ? 1 : 0;

        String nwColor = legacyColor(1, yaw6, markerX6, markerY6, sideBit == 1);
        String neColor = legacyColor(2, yaw6, markerX6, markerY6, sideBit == 1);
        String swColor = legacyColor(3, yaw6, markerX6, markerY6, sideBit == 1);
        String seColor = legacyColor(4, yaw6, markerX6, markerY6, sideBit == 1);
        String borderColor = legacyColor(5, yaw6, 0, 0, sideBit == 1);
        String markerColor = legacyColor(6, yaw6, markerX6, markerY6, sideBit == 1);

        String leftOrRight = sideBit == 1 ? "right" : "left";

        return layout
                .replace("{nw}", nwColor + nw)
                .replace("{ne}", neColor + ne)
                .replace("{sw}", swColor + sw)
                .replace("{se}", seColor + se)
                .replace("{border}", borderColor + border)
                .replace("{marker}", markerColor + marker)
                .replace("{side}", leftOrRight)
                + LEGACY_PREFIX + "r";
    }

    private static int encodeUnsigned6FromPixels(double value) {
        int payload = (int) Math.round(value + LEGACY_MAX_PAN_PIXELS);
        return (int) clamp(payload, 0, 63);
    }

    private static int encodeUnsigned6FromDegrees(double degrees) {
        int payload = (int) Math.round((normalizeUnsignedDegrees(degrees) / 360.0D) * 63.0D);
        return (int) clamp(payload, 0, 63);
    }

    private static double normalizeUnsignedDegrees(double degrees) {
        double normalized = degrees % 360.0D;
        if (normalized < 0.0D) {
            normalized += 360.0D;
        }
        return normalized;
    }

    private static String legacyColor(int type, int yaw6, int panX6, int panY6, boolean sideRight) {
        int yawLow5 = yaw6 & 0x1F;
        int yawHigh1 = (yaw6 >> 5) & 0x01;

        int red = ((type & 0x07) << 5) | yawLow5;
        int green = 0x80 | (yawHigh1 << 6) | (panX6 & 0x3F);
        int blue = (sideRight ? 0x80 : 0x00) | 0x40 | (panY6 & 0x3F);
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
