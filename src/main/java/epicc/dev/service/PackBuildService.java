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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import org.bukkit.configuration.file.FileConfiguration;

public final class PackBuildService {
    private static final String TEXT_SHADER_VERTEX = """
            #version 330
            #moj_import <minecraft:fog.glsl>
            #moj_import <minecraft:dynamictransforms.glsl>
            #moj_import <minecraft:projection.glsl>
            #moj_import <_198bbb70afab9c3d.glsl>

            in vec3 Position;
            in vec4 Color;
            in vec2 UV0;
            in ivec2 UV2;

            uniform sampler2D Sampler2;

            out float sphericalVertexDistance;
            out float cylindricalVertexDistance;
            out vec4 vertexColor;
            out vec2 texCoord0;

            void main() {
                vec3 position = Position;
                ivec4 color8 = _7a81e42fddee2f93(Color);

                // Marker glyph uses signed 6-bit payload with channel signatures:
                // R: 01xxxxxx, G: 10xxxxxx, B: 11xxxxxx
                // This avoids shifting normal text/shadow vertices.
                bool minimapMarker =
                    (color8.r & 0xC0) == 0x40 &&
                    (color8.g & 0xC0) == 0x80 &&
                    (color8.b & 0xC0) == 0xC0;

                if (minimapMarker) {
                    int sx = color8.r & 0x3F;
                    int sy = color8.g & 0x3F;
                    vec2 offset = vec2(float(sx), float(sy));
                    offset = (offset - vec2(31.5)) * 2.0;
                    position.xy += offset;
                }

                gl_Position = ProjMat * ModelViewMat * vec4(position, 1.0);
                sphericalVertexDistance = fog_spherical_distance(position);
                cylindricalVertexDistance = fog_cylindrical_distance(position);
                vertexColor = Color * texelFetch(Sampler2, UV2 / 16, 0);
                texCoord0 = UV0;
            }
            """;

    private static final String TEXT_SHADER_FRAGMENT = """
            #version 330
            #moj_import <minecraft:fog.glsl>
            #moj_import <minecraft:dynamictransforms.glsl>
            #moj_import <minecraft:globals.glsl>

            uniform sampler2D Sampler0;

            in float sphericalVertexDistance;
            in float cylindricalVertexDistance;
            in vec4 vertexColor;
            in vec2 texCoord0;

            out vec4 fragColor;

            void main() {
                vec4 color = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;
                if (color.a < 0.001) {
                    discard;
                }

                fragColor = apply_fog(
                    color,
                    sphericalVertexDistance,
                    cylindricalVertexDistance,
                    FogEnvironmentalStart,
                    FogEnvironmentalEnd,
                    FogRenderDistanceStart,
                    FogRenderDistanceEnd,
                    FogColor
                );
            }
            """;

    private static final String INCLUDE_198 = """
            ivec4 _7a81e42fddee2f93(vec4 _e30c3475a3a85ac1){
                return ivec4(round(_e30c3475a3a85ac1 * 255));
            }

            int _27e4a854910d5a3f(vec4 _e30c3475a3a85ac1){
                ivec4 _8191e12dd310f85c = _7a81e42fddee2f93(_e30c3475a3a85ac1);
                return _8191e12dd310f85c.r << 16 | _8191e12dd310f85c.g << 8 | _8191e12dd310f85c.b;
            }

            vec4 _e72020d0026b8201(ivec4 _e30c3475a3a85ac1){
                return vec4(_e30c3475a3a85ac1) / 255;
            }
            """;

    private static final String LINES_VERTEX = """
            #version 330
            #moj_import <minecraft:fog.glsl>
            #moj_import <minecraft:globals.glsl>
            #moj_import <minecraft:dynamictransforms.glsl>
            #moj_import <minecraft:projection.glsl>

            in vec3 Position;
            in vec4 Color;
            in vec3 Normal;
            in float LineWidth;

            out float sphericalVertexDistance;
            out float cylindricalVertexDistance;
            out vec4 vertexColor;

            const float VIEW_SHRINK = 1.0 - (1.0 / 256.0);
            const mat4 VIEW_SCALE = mat4(
                VIEW_SHRINK, 0.0, 0.0, 0.0,
                0.0, VIEW_SHRINK, 0.0, 0.0,
                0.0, 0.0, VIEW_SHRINK, 0.0,
                0.0, 0.0, 0.0, 1.0
            );

            void main() {
                vec4 linePosStart = ProjMat * VIEW_SCALE * ModelViewMat * vec4(Position, 1.0);
                vec4 linePosEnd = ProjMat * VIEW_SCALE * ModelViewMat * vec4(Position + Normal, 1.0);

                vec3 ndc1 = linePosStart.xyz / linePosStart.w;
                vec3 ndc2 = linePosEnd.xyz / linePosEnd.w;

                vec2 lineScreenDirection = normalize((ndc2.xy - ndc1.xy) * ScreenSize);
                vec2 lineOffset = vec2(-lineScreenDirection.y, lineScreenDirection.x) * LineWidth / ScreenSize;

                if (lineOffset.x < 0.0) {
                    lineOffset *= -1.0;
                }

                if (gl_VertexID % 2 == 0) {
                    gl_Position = vec4((ndc1 + vec3(lineOffset, 0.0)) * linePosStart.w, linePosStart.w);
                } else {
                    gl_Position = vec4((ndc1 - vec3(lineOffset, 0.0)) * linePosStart.w, linePosStart.w);
                }

                sphericalVertexDistance = fog_spherical_distance(Position);
                cylindricalVertexDistance = fog_cylindrical_distance(Position);
                vertexColor = Color;

                if (Color == vec4(0, 0, 0, .4)) {
                    vertexColor = vec4(0, 0, 0, 0);
                    gl_Position = vec4(0);
                }
            }
            """;

    private static final String ITEM_ENTITY_VERTEX = """
            #version 330
            #moj_import <minecraft:light.glsl>
            #moj_import <minecraft:fog.glsl>
            #moj_import <minecraft:dynamictransforms.glsl>
            #moj_import <minecraft:projection.glsl>
            #moj_import <minecraft:globals.glsl>

            in vec3 Position;
            in vec4 Color;
            in vec2 UV0;
            in vec2 UV1;
            in ivec2 UV2;
            in vec3 Normal;

            uniform sampler2D Sampler2;

            out float sphericalVertexDistance;
            out float cylindricalVertexDistance;
            out vec4 vertexColor;
            out vec2 texCoord0;
            out vec2 texCoord1;

            void main() {
                gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
                sphericalVertexDistance = fog_spherical_distance(Position);
                cylindricalVertexDistance = fog_cylindrical_distance(Position);
                vertexColor = minecraft_mix_light(Light0_Direction, Light1_Direction, Normal, Color) * texelFetch(Sampler2, UV2 / 16, 0);
                texCoord0 = UV0;
                texCoord1 = UV1;

                ivec4 marker = ivec4(Color * 255);
                if (marker == ivec4(0, 0, 2, 255)) {
                    gl_Position.z = 0;
                    if (ModelViewMat[3][2] == -11000.0) {
                        vertexColor = vec4(0);
                    } else {
                        vertexColor = vec4(0.0, 0.0, 0.0, 1.0);
                    }
                }
            }
            """;

    private static final String ITEM_ENTITY_FRAGMENT = """
            #version 330
            #moj_import <minecraft:fog.glsl>
            #moj_import <minecraft:dynamictransforms.glsl>
            #moj_import <minecraft:globals.glsl>

            uniform sampler2D Sampler0;

            in float sphericalVertexDistance;
            in float cylindricalVertexDistance;
            in vec4 vertexColor;
            in vec2 texCoord0;
            in vec2 texCoord1;

            out vec4 fragColor;

            void main() {
                vec4 color = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;
                if (color.a < 0.1) {
                    discard;
                }

                fragColor = apply_fog(
                    color,
                    sphericalVertexDistance,
                    cylindricalVertexDistance,
                    FogEnvironmentalStart,
                    FogEnvironmentalEnd,
                    FogRenderDistanceStart,
                    FogRenderDistanceEnd,
                    FogColor
                );
            }
            """;

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
        String nwGlyph = decodeUnicodeEscapes(config.getString("hud.glyphs.nw", "\\uE101"));
        String neGlyph = decodeUnicodeEscapes(config.getString("hud.glyphs.ne", "\\uE102"));
        String swGlyph = decodeUnicodeEscapes(config.getString("hud.glyphs.sw", "\\uE103"));
        String seGlyph = decodeUnicodeEscapes(config.getString("hud.glyphs.se", "\\uE104"));
        String borderGlyph = decodeUnicodeEscapes(config.getString("hud.glyphs.border", "\\uE105"));
        String markerGlyph = decodeUnicodeEscapes(config.getString("hud.glyphs.marker", "\\uE106"));

        writeTextFileIfNeeded(contentsPath.resolve("minecraft/shaders/core/rendertype_text.vsh"), TEXT_SHADER_VERTEX, overwrite);
        writeTextFileIfNeeded(contentsPath.resolve("minecraft/shaders/core/rendertype_text.fsh"), TEXT_SHADER_FRAGMENT, overwrite);
        writeTextFileIfNeeded(contentsPath.resolve("minecraft/shaders/core/rendertype_lines.vsh"), LINES_VERTEX, overwrite);
        writeTextFileIfNeeded(contentsPath.resolve("minecraft/shaders/core/rendertype_item_entity_translucent_cull.vsh"), ITEM_ENTITY_VERTEX, overwrite);
        writeTextFileIfNeeded(contentsPath.resolve("minecraft/shaders/core/rendertype_item_entity_translucent_cull.fsh"), ITEM_ENTITY_FRAGMENT, overwrite);
        writeTextFileIfNeeded(contentsPath.resolve("minecraft/shaders/include/_198bbb70afab9c3d.glsl"), INCLUDE_198, overwrite);

        String providers = "{\n"
                + "  \"providers\": [\n"
                + "    " + bitmapProvider("minecraft:font/minimap/nw.png", 128, 128, nwGlyph) + ",\n"
                + "    " + bitmapProvider("minecraft:font/minimap/ne.png", 128, 128, neGlyph) + ",\n"
                + "    " + bitmapProvider("minecraft:font/minimap/sw.png", 128, 128, swGlyph) + ",\n"
                + "    " + bitmapProvider("minecraft:font/minimap/se.png", 128, 128, seGlyph) + ",\n"
                + "    " + bitmapProvider("minecraft:font/minimap/border.png", 128, 128, borderGlyph) + ",\n"
                + "    " + bitmapProvider("minecraft:font/minimap/marker.png", 32, 24, markerGlyph) + "\n"
                + "  ]\n"
                + "}\n";
        writeTextFileIfNeeded(contentsPath.resolve("minecraft/font/default.json"), providers, overwrite);

        writeTextureIfNeeded(contentsPath.resolve("minecraft/textures/font/minimap/nw.png"), 128, new Color(52, 107, 164, 255), PlaceholderMode.FILL, overwrite);
        writeTextureIfNeeded(contentsPath.resolve("minecraft/textures/font/minimap/ne.png"), 128, new Color(71, 128, 181, 255), PlaceholderMode.FILL, overwrite);
        writeTextureIfNeeded(contentsPath.resolve("minecraft/textures/font/minimap/sw.png"), 128, new Color(63, 116, 170, 255), PlaceholderMode.FILL, overwrite);
        writeTextureIfNeeded(contentsPath.resolve("minecraft/textures/font/minimap/se.png"), 128, new Color(88, 141, 196, 255), PlaceholderMode.FILL, overwrite);
        writeTextureIfNeeded(contentsPath.resolve("minecraft/textures/font/minimap/border.png"), 128, new Color(235, 235, 235, 255), PlaceholderMode.BORDER, overwrite);
        writeTextureIfNeeded(contentsPath.resolve("minecraft/textures/font/minimap/marker.png"), 32, new Color(255, 44, 44, 255), PlaceholderMode.MARKER, overwrite);
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
        BORDER,
        MARKER
    }

    public record BuildArtifact(UUID id, byte[] sha1, Path zipPath, long builtAt, String sha1Hex) {
    }
}
