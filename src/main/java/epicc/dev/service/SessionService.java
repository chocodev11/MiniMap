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

    public boolean isHudEnabled(UUID playerId) {
        return ensure(playerId).hudEnabled;
    }

    public void setHudEnabled(UUID playerId, boolean enabled) {
        ensure(playerId).hudEnabled = enabled;
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

        public boolean hudEnabled() {
            return this.hudEnabled;
        }

        public boolean packLoaded() {
            return this.packLoaded;
        }
    }
}
