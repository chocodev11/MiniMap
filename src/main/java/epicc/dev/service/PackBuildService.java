package epicc.dev.service;

import epicc.dev.MiniMap;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import org.bukkit.configuration.file.FileConfiguration;

public final class PackBuildService {
    private final MiniMap plugin;
    private BuildArtifact currentArtifact;

    public PackBuildService(MiniMap plugin) {
        this.plugin = plugin;
    }

    public synchronized BuildArtifact getCurrentArtifact() {
        if (this.currentArtifact == null || !Files.exists(this.currentArtifact.zipPath())) {
            return rebuildPack();
        }

        return this.currentArtifact;
    }

    public synchronized BuildArtifact rebuildPack() {
        try {
            FileConfiguration config = this.plugin.getConfig();
            Path dataPath = this.plugin.getDataFolder().toPath();
            Files.createDirectories(dataPath);

            Path workingPath = dataPath.resolve("pack-work");
            deleteDirectory(workingPath);
            Files.createDirectories(workingPath);

            copyConfiguredFiles(config, workingPath);
            writePackMcmeta(config, workingPath);
            writeDefaultFont(config, workingPath);

            String outputFile = config.getString("pack.build.outputFile", "minimap-pack.zip");
            Path outputPath = dataPath.resolve(outputFile).normalize();
            zipDirectory(workingPath, outputPath);

            byte[] sha1 = sha1(outputPath);
            String shaHex = toHex(sha1);
            UUID packId = UUID.nameUUIDFromBytes(sha1);

            this.currentArtifact = new BuildArtifact(packId, sha1, outputPath, System.currentTimeMillis(), shaHex);
            this.plugin.getLogger().info("Resource pack rebuilt: " + outputPath + " (sha1=" + shaHex + ")");
            return this.currentArtifact;
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to build minimap pack", exception);
        }
    }

    private void copyConfiguredFiles(FileConfiguration config, Path workingPath) throws IOException {
        List<String> entries = config.getStringList("pack.build.files");
        String sourceRootRaw = config.getString("pack.build.sourceRoot", "..");

        if (entries.isEmpty()) {
            this.plugin.getLogger().warning("pack.build.files is empty. Only generated pack files will be included.");
            return;
        }

        for (String entry : entries) {
            CopyEntry copyEntry = parseCopyEntry(entry);
            if (copyEntry == null) {
                this.plugin.getLogger().warning("Invalid pack.build entry: " + entry);
                continue;
            }

            Path sourcePath = resolveSourcePath(copyEntry.source(), sourceRootRaw);
            if (sourcePath == null || !Files.exists(sourcePath)) {
                this.plugin.getLogger().warning("Source file not found: " + copyEntry.source());
                continue;
            }

            Path targetPath = normalizeTargetPath(workingPath, copyEntry.target());
            if (targetPath == null) {
                this.plugin.getLogger().warning("Rejected unsafe target path: " + copyEntry.target());
                continue;
            }

            Files.createDirectories(targetPath.getParent());
            Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void writePackMcmeta(FileConfiguration config, Path workingPath) throws IOException {
        int packFormat = config.getInt("pack.build.packFormat", 75);
        String description = escapeJson(config.getString("pack.build.description", "MiniMap PoC Pack"));

        String json = "{\n"
                + "  \"pack\": {\n"
                + "    \"pack_format\": " + packFormat + ",\n"
                + "    \"description\": \"" + description + "\"\n"
                + "  }\n"
                + "}\n";

        Path mcmetaPath = workingPath.resolve("pack.mcmeta");
        Files.writeString(mcmetaPath, json, StandardCharsets.UTF_8);
    }

    private void writeDefaultFont(FileConfiguration config, Path workingPath) throws IOException {
        Path fontPath = workingPath.resolve("assets/minecraft/font/default.json");
        Files.createDirectories(fontPath.getParent());

        String nwGlyph = decodeUnicodeEscapes(config.getString("hud.glyphs.nw", "\\uE101"));
        String neGlyph = decodeUnicodeEscapes(config.getString("hud.glyphs.ne", "\\uE102"));
        String swGlyph = decodeUnicodeEscapes(config.getString("hud.glyphs.sw", "\\uE103"));
        String seGlyph = decodeUnicodeEscapes(config.getString("hud.glyphs.se", "\\uE104"));
        String borderGlyph = decodeUnicodeEscapes(config.getString("hud.glyphs.border", "\\uE105"));
        String markerGlyph = decodeUnicodeEscapes(config.getString("hud.glyphs.marker", "\\uE106"));

        ensureTextureExists(workingPath.resolve("assets/minecraft/textures/font/minimap/nw.png"), 128, new Color(60, 100, 180, 255), PlaceholderMode.FILL);
        ensureTextureExists(workingPath.resolve("assets/minecraft/textures/font/minimap/ne.png"), 128, new Color(90, 140, 200, 255), PlaceholderMode.FILL);
        ensureTextureExists(workingPath.resolve("assets/minecraft/textures/font/minimap/sw.png"), 128, new Color(80, 120, 160, 255), PlaceholderMode.FILL);
        ensureTextureExists(workingPath.resolve("assets/minecraft/textures/font/minimap/se.png"), 128, new Color(110, 150, 210, 255), PlaceholderMode.FILL);
        ensureTextureExists(workingPath.resolve("assets/minecraft/textures/font/minimap/border.png"), 128, new Color(220, 220, 220, 255), PlaceholderMode.BORDER);
        ensureTextureExists(workingPath.resolve("assets/minecraft/textures/font/minimap/marker.png"), 16, new Color(255, 30, 30, 255), PlaceholderMode.MARKER);

        List<String> providers = new ArrayList<>();
        providers.add(bitmapProvider("minecraft:font/minimap/nw.png", 128, 128, nwGlyph));
        providers.add(bitmapProvider("minecraft:font/minimap/ne.png", 128, 128, neGlyph));
        providers.add(bitmapProvider("minecraft:font/minimap/sw.png", 128, 128, swGlyph));
        providers.add(bitmapProvider("minecraft:font/minimap/se.png", 128, 128, seGlyph));
        providers.add(bitmapProvider("minecraft:font/minimap/border.png", 128, 128, borderGlyph));
        providers.add(bitmapProvider("minecraft:font/minimap/marker.png", 16, 16, markerGlyph));

        StringBuilder json = new StringBuilder();
        json.append("{\n  \"providers\": [\n");

        for (int i = 0; i < providers.size(); i++) {
            json.append("    ").append(providers.get(i));
            if (i + 1 < providers.size()) {
                json.append(',');
            }
            json.append('\n');
        }

        json.append("  ]\n}\n");
        Files.writeString(fontPath, json, StandardCharsets.UTF_8);
    }

    private String bitmapProvider(String filePath, int height, int ascent, String glyph) {
        return "{\"type\":\"bitmap\",\"file\":\"" + filePath + "\",\"height\":" + height + ",\"ascent\":" + ascent
                + ",\"chars\":[\"" + escapeGlyph(glyph) + "\"]}";
    }

    private void ensureTextureExists(Path targetPath, int size, Color color, PlaceholderMode mode) throws IOException {
        if (Files.exists(targetPath)) {
            return;
        }

        Files.createDirectories(targetPath.getParent());
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        if (mode == PlaceholderMode.FILL) {
            graphics.setColor(color);
            graphics.fillRect(0, 0, size, size);
        } else if (mode == PlaceholderMode.BORDER) {
            graphics.setColor(new Color(0, 0, 0, 0));
            graphics.fillRect(0, 0, size, size);
            graphics.setColor(color);
            graphics.setStroke(new BasicStroke(Math.max(4, size / 16f)));
            graphics.drawOval(4, 4, size - 8, size - 8);
        } else {
            graphics.setColor(new Color(0, 0, 0, 0));
            graphics.fillRect(0, 0, size, size);
            graphics.setColor(color);
            int center = size / 2;
            int[] xPoints = {center, center - (size / 4), center + (size / 4)};
            int[] yPoints = {size / 6, size - (size / 5), size - (size / 5)};
            graphics.fillPolygon(xPoints, yPoints, 3);
        }

        graphics.dispose();
        ImageIO.write(image, "png", targetPath.toFile());
    }

    private static void zipDirectory(Path sourceDir, Path zipPath) throws IOException {
        Files.createDirectories(zipPath.getParent());

        try (OutputStream outputStream = Files.newOutputStream(zipPath);
             ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream, StandardCharsets.UTF_8)) {
            try (var stream = Files.walk(sourceDir)) {
                stream.filter(Files::isRegularFile).forEach(path -> {
                    Path relative = sourceDir.relativize(path);
                    ZipEntry zipEntry = new ZipEntry(relative.toString().replace('\\', '/'));

                    try {
                        zipOutputStream.putNextEntry(zipEntry);
                        Files.copy(path, zipOutputStream);
                        zipOutputStream.closeEntry();
                    } catch (IOException exception) {
                        throw new IllegalStateException("Unable to write zip entry " + relative, exception);
                    }
                });
            }
        }
    }

    private static byte[] sha1(Path filePath) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-1 digest not available", exception);
        }

        try (InputStream inputStream = Files.newInputStream(filePath)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = inputStream.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }

        return digest.digest();
    }

    private static String toHex(byte[] value) {
        StringBuilder builder = new StringBuilder(value.length * 2);
        for (byte b : value) {
            builder.append(String.format(Locale.ROOT, "%02x", b));
        }
        return builder.toString();
    }

    private static void deleteDirectory(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }

        try (var stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder()).forEach(current -> {
                try {
                    Files.deleteIfExists(current);
                } catch (IOException exception) {
                    throw new IllegalStateException("Unable to delete " + current, exception);
                }
            });
        }
    }

    private Path resolveSourcePath(String source, String sourceRootRaw) {
        List<Path> candidates = new ArrayList<>();
        Path dataPath = this.plugin.getDataFolder().toPath();
        Path cwdPath = Path.of("").toAbsolutePath();

        Path direct = Path.of(source);
        candidates.add(direct);

        Path sourceRoot = Path.of(sourceRootRaw);
        candidates.add(sourceRoot.resolve(source));

        candidates.add(dataPath.resolve(source));
        if (dataPath.getParent() != null) {
            candidates.add(dataPath.getParent().resolve(source));
            candidates.add(dataPath.getParent().resolve(sourceRootRaw).resolve(source));
        }

        candidates.add(cwdPath.resolve(source));
        candidates.add(cwdPath.resolve(sourceRootRaw).resolve(source));

        for (Path candidate : candidates) {
            Path normalized = candidate.normalize();
            if (Files.exists(normalized)) {
                return normalized;
            }
        }

        return null;
    }

    private static String escapeJson(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "")
                .replace("\t", "\\t");
    }

    private static String escapeGlyph(String glyph) {
        if (glyph == null || glyph.isEmpty()) {
            return " ";
        }

        int codePoint = glyph.codePointAt(0);
        if (codePoint <= 0xFFFF) {
            return String.format("\\u%04X", codePoint);
        }

        return new String(Character.toChars(codePoint));
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

    private static Path normalizeTargetPath(Path root, String target) {
        Path path = root.resolve(target).normalize();
        if (!path.startsWith(root)) {
            return null;
        }
        return path;
    }

    private static CopyEntry parseCopyEntry(String entry) {
        int split = entry.indexOf('|');
        if (split <= 0 || split + 1 >= entry.length()) {
            return null;
        }

        String source = entry.substring(0, split).trim();
        String target = entry.substring(split + 1).trim();
        if (source.isEmpty() || target.isEmpty()) {
            return null;
        }

        return new CopyEntry(source, target);
    }

    private record CopyEntry(String source, String target) {
    }

    private enum PlaceholderMode {
        FILL,
        BORDER,
        MARKER
    }

    public record BuildArtifact(UUID id, byte[] sha1, Path zipPath, long builtAt, String sha1Hex) {
    }
}
