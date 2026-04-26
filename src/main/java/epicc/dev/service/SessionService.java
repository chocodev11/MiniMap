package epicc.dev.service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SessionService {
    private final Map<UUID, SessionState> sessions = new ConcurrentHashMap<>();

    public SessionState ensure(UUID playerId) {
        return this.sessions.computeIfAbsent(playerId, ignored -> new SessionState());
    }

    public boolean isPackLoaded(UUID playerId) {
        return ensure(playerId).packLoaded;
    }

    public void setPackLoaded(UUID playerId, boolean loaded) {
        ensure(playerId).packLoaded = loaded;
    }

    public boolean isPackRequestPending(UUID playerId) {
        return ensure(playerId).packRequestPending;
    }

    public void setPackRequestPending(UUID playerId, boolean pending) {
        ensure(playerId).packRequestPending = pending;
    }

    public UUID getLastRequestedPackId(UUID playerId) {
        return ensure(playerId).lastRequestedPackId;
    }

    public void setLastRequestedPackId(UUID playerId, UUID packId) {
        ensure(playerId).lastRequestedPackId = packId;
    }

    public boolean isHudEnabled(UUID playerId) {
        return ensure(playerId).hudEnabled;
    }

    public void setHudEnabled(UUID playerId, boolean enabled) {
        ensure(playerId).hudEnabled = enabled;
    }

    public String getLastHudTitle(UUID playerId) {
        return ensure(playerId).lastHudTitle;
    }

    public void setLastHudTitle(UUID playerId, String title) {
        ensure(playerId).lastHudTitle = title;
    }

    public void clearLastHudTitle(UUID playerId) {
        ensure(playerId).lastHudTitle = null;
    }

    public double smoothYawDegrees(UUID playerId, double targetDegrees, double alpha) {
        SessionState state = ensure(playerId);
        double normalizedTarget = normalizeUnsignedDegrees(targetDegrees);
        if (!state.smoothedYawInitialized) {
            state.smoothedYawDegrees = normalizedTarget;
            state.smoothedYawInitialized = true;
            return normalizedTarget;
        }

        double clampedAlpha = clamp(alpha, 0.0D, 1.0D);
        if (clampedAlpha <= 0.0D) {
            return state.smoothedYawDegrees;
        }

        if (clampedAlpha >= 1.0D) {
            state.smoothedYawDegrees = normalizedTarget;
            return normalizedTarget;
        }

        double delta = normalizeSignedDegrees(normalizedTarget - state.smoothedYawDegrees);
        state.smoothedYawDegrees = normalizeUnsignedDegrees(state.smoothedYawDegrees + (delta * clampedAlpha));
        return state.smoothedYawDegrees;
    }

    public void clearSmoothedYaw(UUID playerId) {
        SessionState state = ensure(playerId);
        state.smoothedYawDegrees = 0.0D;
        state.smoothedYawInitialized = false;
    }

    public void remove(UUID playerId) {
        this.sessions.remove(playerId);
    }

    public void clear() {
        this.sessions.clear();
    }

    private static double normalizeUnsignedDegrees(double degrees) {
        double normalized = degrees % 360.0D;
        if (normalized < 0.0D) {
            normalized += 360.0D;
        }
        return normalized;
    }

    private static double normalizeSignedDegrees(double degrees) {
        double normalized = normalizeUnsignedDegrees(degrees);
        if (normalized > 180.0D) {
            normalized -= 360.0D;
        }
        return normalized;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public static final class SessionState {
        private boolean hudEnabled = true;
        private boolean packLoaded;
        private boolean packRequestPending;
        private UUID lastRequestedPackId;
        private String lastHudTitle;
        private double smoothedYawDegrees;
        private boolean smoothedYawInitialized;

        public boolean hudEnabled() {
            return this.hudEnabled;
        }

        public boolean packLoaded() {
            return this.packLoaded;
        }

        public boolean packRequestPending() {
            return this.packRequestPending;
        }

        public UUID lastRequestedPackId() {
            return this.lastRequestedPackId;
        }

        public String lastHudTitle() {
            return this.lastHudTitle;
        }

        public double smoothedYawDegrees() {
            return this.smoothedYawDegrees;
        }

        public boolean smoothedYawInitialized() {
            return this.smoothedYawInitialized;
        }
    }
}
