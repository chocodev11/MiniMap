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

    public void remove(UUID playerId) {
        this.sessions.remove(playerId);
    }

    public void clear() {
        this.sessions.clear();
    }

    public static final class SessionState {
        private boolean hudEnabled = true;
        private boolean packLoaded;
        private boolean packRequestPending;
        private UUID lastRequestedPackId;
        private String lastHudTitle;

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
    }
}
