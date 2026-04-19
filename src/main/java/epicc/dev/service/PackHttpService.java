package epicc.dev.service;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import epicc.dev.MiniMap;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;

public final class PackHttpService {
    private final MiniMap plugin;
    private final PackBuildService packBuildService;

    private HttpServer server;
    private ExecutorService executorService;
    private String endpointPath = "/minimap-pack.zip";
    private String bindAddress = "0.0.0.0";
    private int bindPort = 8085;

    public PackHttpService(MiniMap plugin, PackBuildService packBuildService) {
        this.plugin = plugin;
        this.packBuildService = packBuildService;
    }

    public synchronized void start() {
        stop();

        try {
            FileConfiguration config = this.plugin.getConfig();
            this.bindAddress = config.getString("pack.host.bind", "0.0.0.0");
            this.bindPort = Math.max(1, config.getInt("pack.host.port", 8085));
            this.endpointPath = normalizeEndpoint(config.getString("pack.host.path", "minimap-pack.zip"));

            this.server = HttpServer.create(new InetSocketAddress(this.bindAddress, this.bindPort), 0);
            this.server.createContext("/health", new HealthHandler());
            this.server.createContext(this.endpointPath, new PackHandler(this.packBuildService));
            this.server.createContext("/", exchange -> writeResponse(exchange, 404, "Not Found"));

            this.executorService = Executors.newFixedThreadPool(2);
            this.server.setExecutor(this.executorService);
            this.server.start();

            this.plugin.getLogger().info("Pack HTTP server started at " + this.bindAddress + ":" + this.bindPort + this.endpointPath);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to start Pack HTTP server", exception);
        }
    }

    public synchronized void restart() {
        start();
    }

    public synchronized void stop() {
        if (this.server != null) {
            this.server.stop(0);
            this.server = null;
        }

        if (this.executorService != null) {
            this.executorService.shutdownNow();
            this.executorService = null;
        }
    }

    public String getPackUrl() {
        PackBuildService.BuildArtifact artifact = this.packBuildService.getCurrentArtifact();
        String baseUrl = resolveBaseUrl();
        return baseUrl + this.endpointPath + "?v=" + artifact.builtAt();
    }

    private String resolveBaseUrl() {
        String configured = this.plugin.getConfig().getString("pack.host.publicBaseUrl", "").trim();
        if (!configured.isEmpty()) {
            return stripTrailingSlash(configured);
        }

        String host;
        if ("0.0.0.0".equals(this.bindAddress) || "::".equals(this.bindAddress)) {
            host = Bukkit.getIp();
            if (host == null || host.isBlank()) {
                host = "127.0.0.1";
            }
        } else {
            host = this.bindAddress;
        }

        return "http://" + host + ":" + this.bindPort;
    }

    private static String normalizeEndpoint(String path) {
        String normalized = Objects.requireNonNullElse(path, "minimap-pack.zip").trim();
        if (normalized.isEmpty()) {
            normalized = "minimap-pack.zip";
        }

        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }

        return normalized;
    }

    private static String stripTrailingSlash(String value) {
        if (value.endsWith("/")) {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static void writeResponse(HttpExchange exchange, int status, String content) throws IOException {
        byte[] bytes = content.getBytes();
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(bytes);
        }
    }

    private static final class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            writeResponse(exchange, 200, "ok");
        }
    }

    private static final class PackHandler implements HttpHandler {
        private final PackBuildService packBuildService;

        private PackHandler(PackBuildService packBuildService) {
            this.packBuildService = packBuildService;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
            if (!"GET".equals(method) && !"HEAD".equals(method)) {
                writeResponse(exchange, 405, "Method Not Allowed");
                return;
            }

            Path packPath = this.packBuildService.getCurrentArtifact().zipPath();
            if (!Files.exists(packPath)) {
                writeResponse(exchange, 404, "Pack file not found");
                return;
            }

            long contentLength = Files.size(packPath);
            exchange.getResponseHeaders().set("Content-Type", "application/zip");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache, no-store, must-revalidate");
            exchange.getResponseHeaders().set("Pragma", "no-cache");
            exchange.getResponseHeaders().set("Expires", "0");
            exchange.sendResponseHeaders(200, "HEAD".equals(method) ? -1 : contentLength);

            if ("GET".equals(method)) {
                try (OutputStream outputStream = exchange.getResponseBody()) {
                    Files.copy(packPath, outputStream);
                }
            }

            exchange.close();
        }
    }
}
