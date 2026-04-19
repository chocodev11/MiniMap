package epicc.dev.service;

import epicc.dev.MiniMap;
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

public final class HudService {
    private static final String LEGACY_PREFIX = "\u00A7";

    private final MiniMap plugin;
    private final SessionService sessionService;
    private final Map<UUID, BossBar> bars = new ConcurrentHashMap<>();

    public HudService(MiniMap plugin, SessionService sessionService) {
        this.plugin = plugin;
        this.sessionService = sessionService;
    }

    public void showHud(Player player) {
        UUID playerId = player.getUniqueId();
        if (!this.sessionService.isHudEnabled(playerId) || !this.sessionService.isPackLoaded(playerId)) {
            return;
        }

        BossBar bar = this.bars.computeIfAbsent(playerId, ignored -> Bukkit.createBossBar("", BarColor.WHITE, BarStyle.SOLID));
        if (!bar.getPlayers().contains(player)) {
            bar.addPlayer(player);
        }

        bar.setVisible(true);
        bar.setProgress(1.0D);
        bar.setTitle(buildHudTitle(player));
    }

    public void hideHud(Player player) {
        BossBar bar = this.bars.get(player.getUniqueId());
        if (bar != null) {
            bar.removePlayer(player);
        }
    }

    public void removeHud(Player player) {
        UUID playerId = player.getUniqueId();
        BossBar bar = this.bars.remove(playerId);
        if (bar != null) {
            bar.removeAll();
        }
    }

    public void updateHud(Player player) {
        UUID playerId = player.getUniqueId();

        if (!this.sessionService.isHudEnabled(playerId)
                || !this.sessionService.isPackLoaded(playerId)
                || !player.hasPermission("minimap.hud")) {
            hideHud(player);
            return;
        }

        BossBar bar = this.bars.computeIfAbsent(playerId, ignored -> Bukkit.createBossBar("", BarColor.WHITE, BarStyle.SOLID));
        if (!bar.getPlayers().contains(player)) {
            bar.addPlayer(player);
        }

        bar.setVisible(true);
        bar.setProgress(1.0D);
        bar.setTitle(buildHudTitle(player));
    }

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

        double normalizedX = clamp((location.getX() - centerX) / radius, -1.0D, 1.0D);
        double normalizedZ = clamp((location.getZ() - centerZ) / radius, -1.0D, 1.0D);
        int yaw5 = encodeUnsigned5FromYaw(location.getYaw());
        int markerX6 = encodeUnsigned6FromSigned(normalizedX);
        int markerY6 = encodeUnsigned6FromSigned(normalizedZ);
        int sideBit = "right".equalsIgnoreCase(config.getString("hud.map.leftOrRight", "left")) ? 1 : 0;

        String nwColor = minimapColor(1, yaw5, markerX6, markerY6, sideBit == 1);
        String neColor = minimapColor(2, yaw5, markerX6, markerY6, sideBit == 1);
        String swColor = minimapColor(3, yaw5, markerX6, markerY6, sideBit == 1);
        String seColor = minimapColor(4, yaw5, markerX6, markerY6, sideBit == 1);
        String borderColor = minimapColor(5, yaw5, 0, 0, sideBit == 1);
        String markerColor = minimapColor(6, yaw5, markerX6, markerY6, sideBit == 1);

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

    private static int encodeUnsigned6FromSigned(double value) {
        int payload = (int) Math.round((value + 1.0D) * 31.5D);
        return (int) clamp(payload, 0, 63);
    }

    private static int encodeUnsigned5FromYaw(float yawDegrees) {
        double wrappedYaw = yawDegrees % 360.0D;
        if (wrappedYaw < 0.0D) {
            wrappedYaw += 360.0D;
        }

        int payload = (int) Math.round((wrappedYaw / 360.0D) * 31.0D);
        return (int) clamp(payload, 0, 31);
    }

    private static String minimapColor(int type, int yaw5, int panX6, int panY6, boolean sideRight) {
        int red = ((type & 0x07) << 5) | (yaw5 & 0x1F);
        int green = 0x80 | (panX6 & 0x3F);
        int blue = (sideRight ? 0xC0 : 0x40) | (panY6 & 0x3F);
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
