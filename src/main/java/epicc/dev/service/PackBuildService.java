package epicc.dev.service;

import epicc.dev.MiniMap;
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
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import org.bukkit.configuration.file.FileConfiguration;

public final class PackBuildService {
    private static final String BORDER_RESOURCE_NAME = "border.png";
    private static final String DEFAULT_SHADER_ROOT = "pack-defaults";
    private static final List<String> DEFAULT_SHADER_FILES = List.of(
            "core/rendertype_text.vsh",
            "core/rendertype_text.fsh",
            "core/rendertype_lines.vsh",
            "core/rendertype_item_entity_translucent_cull.vsh",
            "core/rendertype_item_entity_translucent_cull.fsh",
            "include/color_utils.glsl"
    );

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

            Path contentsPath = dataPath.resolve(config.getString("pack.pipeline.contentsDir", "contents")).normalize();
            Path outputPath = dataPath.resolve(config.getString("pack.pipeline.outputDir", "output")).normalize();
            Path stagePath = outputPath.resolve(".staging");
            Path uncompressedPath = outputPath.resolve("output_uncompressed");
            Path generatedZipPath = outputPath.resolve(config.getString("pack.pipeline.generatedZipName", "generated.zip")).normalize();

            Files.createDirectories(contentsPath);
            Files.createDirectories(outputPath);

            boolean generateDefaults = config.getBoolean("pack.pipeline.generateDefaults", true);
            boolean overwriteDefaults = config.getBoolean("pack.pipeline.overwriteDefaults", false);
            if (generateDefaults) {
                ensureDefaultContents(config, contentsPath, overwriteDefaults);
            }

            deleteDirectory(stagePath);
            Files.createDirectories(stagePath);

            writePackMcmeta(config, stagePath);
            compileContentsToStage(contentsPath, stagePath);

            if (config.getBoolean("pack.pipeline.exportUncompressed", true)) {
                deleteDirectory(uncompressedPath);
                copyDirectory(stagePath, uncompressedPath);
            }

            zipDirectory(stagePath, generatedZipPath);

            byte[] sha1 = sha1(generatedZipPath);
            String shaHex = toHex(sha1);
            UUID packId = UUID.nameUUIDFromBytes(sha1);

            this.currentArtifact = new BuildArtifact(packId, sha1, generatedZipPath, System.currentTimeMillis(), shaHex);
            this.plugin.getLogger().info("Resource pack rebuilt: " + generatedZipPath + " (sha1=" + shaHex + ")");
            return this.currentArtifact;
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to build minimap pack", exception);
        }
    }

    private void ensureDefaultContents(FileConfiguration config, Path contentsPath, boolean overwrite) throws IOException {
        String mode = resolvePipelineMode(config);
        boolean sideRight = "right".equalsIgnoreCase(config.getString("hud.map.leftOrRight", "left"));

        for (String relativeShaderPath : DEFAULT_SHADER_FILES) {
            String resourcePath = DEFAULT_SHADER_ROOT + "/" + mode + "/shaders/" + relativeShaderPath;
            Path outputPath = contentsPath.resolve("minecraft/shaders").resolve(relativeShaderPath);
            String text = readBundledText(resourcePath);
            if ("modern".equals(mode) && relativeShaderPath.endsWith("rendertype_text.vsh")) {
                text = text.replace("__SIDE_RIGHT__", sideRight ? "true" : "false");
            }
            writeTextFileIfNeeded(outputPath, text, overwrite);
        }

        String nwGlyph = decodeUnicodeEscapes(config.getString("hud.glyphs.nw", "\\uE101"));
        String neGlyph = decodeUnicodeEscapes(config.getString("hud.glyphs.ne", "\\uE102"));
        String swGlyph = decodeUnicodeEscapes(config.getString("hud.glyphs.sw", "\\uE103"));
        String seGlyph = decodeUnicodeEscapes(config.getString("hud.glyphs.se", "\\uE104"));
        String borderGlyph = decodeUnicodeEscapes(config.getString("hud.glyphs.border", "\\uE105"));
        String markerGlyph = decodeUnicodeEscapes(config.getString("hud.glyphs.marker", "\\uE106"));

        int tileHeight = "modern".equals(mode) ? 8 : 128;
        int markerHeight = "modern".equals(mode) ? 8 : 32;

        int nwAscent = "modern".equals(mode) ? 20 : 128;
        int neAscent = "modern".equals(mode) ? 40 : 128;
        int swAscent = "modern".equals(mode) ? 60 : 128;
        int seAscent = "modern".equals(mode) ? 80 : 128;
        int borderAscent = "modern".equals(mode) ? 100 : 128;
        int markerAscent = "modern".equals(mode) ? 120 : 24;

        String providers = "{\n"
                + "  \"providers\": [\n"
                + "    " + bitmapProvider("minecraft:font/minimap/nw.png", tileHeight, nwAscent, nwGlyph) + ",\n"
                + "    " + bitmapProvider("minecraft:font/minimap/ne.png", tileHeight, neAscent, neGlyph) + ",\n"
                + "    " + bitmapProvider("minecraft:font/minimap/sw.png", tileHeight, swAscent, swGlyph) + ",\n"
                + "    " + bitmapProvider("minecraft:font/minimap/se.png", tileHeight, seAscent, seGlyph) + ",\n"
                + "    " + bitmapProvider("minecraft:font/minimap/border.png", tileHeight, borderAscent, borderGlyph) + ",\n"
                + "    " + bitmapProvider("minecraft:font/minimap/marker.png", markerHeight, markerAscent, markerGlyph) + "\n"
                + "  ]\n"
                + "}\n";
        writeTextFileIfNeeded(contentsPath.resolve("minecraft/font/default.json"), providers, overwrite);

        writeTextureIfNeeded(contentsPath.resolve("minecraft/textures/font/minimap/nw.png"), 128, new Color(52, 107, 164, 255), PlaceholderMode.FILL, overwrite);
        writeTextureIfNeeded(contentsPath.resolve("minecraft/textures/font/minimap/ne.png"), 128, new Color(71, 128, 181, 255), PlaceholderMode.FILL, overwrite);
        writeTextureIfNeeded(contentsPath.resolve("minecraft/textures/font/minimap/sw.png"), 128, new Color(63, 116, 170, 255), PlaceholderMode.FILL, overwrite);
        writeTextureIfNeeded(contentsPath.resolve("minecraft/textures/font/minimap/se.png"), 128, new Color(88, 141, 196, 255), PlaceholderMode.FILL, overwrite);
        writeBorderTexture(contentsPath.resolve("minecraft/textures/font/minimap/border.png"), overwrite);
        writeTextureIfNeeded(contentsPath.resolve("minecraft/textures/font/minimap/marker.png"), 32, new Color(255, 44, 44, 255), PlaceholderMode.MARKER, overwrite);
    }

    private String resolvePipelineMode(FileConfiguration config) {
        String mode = config.getString("hud.pipeline.mode", "legacy");
        if ("modern".equalsIgnoreCase(mode)) {
            return "modern";
        }
        return "legacy";
    }

    private String readBundledText(String resourcePath) throws IOException {
        InputStream stream = this.plugin.getResource(resourcePath);
        if (stream == null) {
            throw new IllegalStateException("Missing bundled resource: " + resourcePath);
        }

        try (InputStream inputStream = stream) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void writeBorderTexture(Path targetPath, boolean overwrite) throws IOException {
        if (Files.exists(targetPath) && !overwrite) {
            return;
        }

        InputStream sourceStream = this.plugin.getResource(BORDER_RESOURCE_NAME);
        if (sourceStream == null) {
            throw new IllegalStateException(
                    "Missing plugin resource: " + BORDER_RESOURCE_NAME + " (expected at src/main/resources/" + BORDER_RESOURCE_NAME + ")");
        }

        Files.createDirectories(targetPath.getParent());
        try (InputStream inputStream = sourceStream) {
            Files.copy(inputStream, targetPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void compileContentsToStage(Path contentsPath, Path stagePath) throws IOException {
        if (!Files.exists(contentsPath)) {
            return;
        }

        try (var stream = Files.walk(contentsPath)) {
            stream.filter(Files::isRegularFile).forEach(sourceFile -> {
                try {
                    Path relative = contentsPath.relativize(sourceFile);
                    if (relative.getNameCount() == 0) {
                        return;
                    }

                    Path targetPath;
                    if ("assets".equals(relative.getName(0).toString())) {
                        targetPath = stagePath.resolve(relative);
                    } else {
                        String namespace = relative.getName(0).toString();
                        Path rest = relative.getNameCount() > 1
                                ? relative.subpath(1, relative.getNameCount())
                                : Path.of(relative.getFileName().toString());
                        targetPath = stagePath.resolve("assets").resolve(namespace).resolve(rest);
                    }

                    targetPath = targetPath.normalize();
                    if (!targetPath.startsWith(stagePath)) {
                        throw new IllegalStateException("Unsafe target path from contents: " + relative);
                    }

                    Files.createDirectories(targetPath.getParent());
                    Files.copy(sourceFile, targetPath);
                } catch (IOException exception) {
                    throw new IllegalStateException("Failed to compile file from contents", exception);
                }
            });
        }
    }

    private void writeTextFileIfNeeded(Path filePath, String content, boolean overwrite) throws IOException {
        if (Files.exists(filePath) && !overwrite) {
            return;
        }

        Files.createDirectories(filePath.getParent());
        Files.writeString(filePath, content, StandardCharsets.UTF_8);
    }

    private void writePackMcmeta(FileConfiguration config, Path stagePath) throws IOException {
        int packFormat = config.getInt("pack.pipeline.packFormat", 75);
        String description = escapeJson(config.getString("pack.pipeline.description", "MiniMap PoC Pack"));

        String json = "{\n"
                + "  \"pack\": {\n"
                + "    \"pack_format\": " + packFormat + ",\n"
                + "    \"description\": \"" + description + "\"\n"
                + "  }\n"
                + "}\n";

        Path mcmetaPath = stagePath.resolve("pack.mcmeta");
        Files.writeString(mcmetaPath, json, StandardCharsets.UTF_8);
    }

    private String bitmapProvider(String filePath, int height, int ascent, String glyph) {
        return "{\"type\":\"bitmap\",\"file\":\"" + filePath + "\",\"height\":" + height + ",\"ascent\":" + ascent
                + ",\"chars\":[\"" + escapeGlyph(glyph) + "\"]}";
    }

    private void writeTextureIfNeeded(Path targetPath, int size, Color color, PlaceholderMode mode, boolean overwrite) throws IOException {
        if (Files.exists(targetPath) && !overwrite) {
            return;
        }

        Files.createDirectories(targetPath.getParent());

        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        if (mode == PlaceholderMode.FILL) {
            graphics.setColor(color);
            graphics.fillRect(0, 0, size, size);
            graphics.setColor(new Color(255, 255, 255, 24));
            for (int x = 0; x < size; x += 16) {
                graphics.drawLine(x, 0, x, size);
            }
            for (int y = 0; y < size; y += 16) {
                graphics.drawLine(0, y, size, y);
            }
        } else {
            graphics.setColor(new Color(0, 0, 0, 0));
            graphics.fillRect(0, 0, size, size);
            graphics.setColor(color);
            int center = size / 2;
            int[] xPoints = {center, center - (size / 4), center + (size / 4)};
            int[] yPoints = {size / 8, size - (size / 5), size - (size / 5)};
            graphics.fillPolygon(xPoints, yPoints, 3);
        }

        graphics.dispose();
        ImageIO.write(image, "png", targetPath.toFile());
    }

    private static void copyDirectory(Path source, Path target) throws IOException {
        Files.createDirectories(target);

        try (var stream = Files.walk(source)) {
            stream.forEach(sourcePath -> {
                try {
                    Path relative = source.relativize(sourcePath);
                    Path targetPath = target.resolve(relative);
                    if (Files.isDirectory(sourcePath)) {
                        Files.createDirectories(targetPath);
                    } else {
                        Files.createDirectories(targetPath.getParent());
                        Files.copy(sourcePath, targetPath);
                    }
                } catch (IOException exception) {
                    throw new IllegalStateException("Failed to copy directory", exception);
                }
            });
        }
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

    private enum PlaceholderMode {
        FILL,
        MARKER
    }

    public record BuildArtifact(UUID id, byte[] sha1, Path zipPath, long builtAt, String sha1Hex) {
    }
}
