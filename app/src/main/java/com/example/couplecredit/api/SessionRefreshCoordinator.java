package com.example.couplecredit.api;

import java.util.Objects;

/** Serializes refreshes without blocking logout or a new login. No Android dependencies. */
public final class SessionRefreshCoordinator {
    public static final class Session {
        public final long generation;
        public final String accessToken, refreshToken, baseUrl;
        public Session(long generation, String accessToken, String refreshToken, String baseUrl) {
            this.generation = generation;
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
            this.baseUrl = baseUrl;
        }
        public boolean sameIdentity(Session other) {
            return other != null && generation == other.generation
                    && Objects.equals(baseUrl, other.baseUrl);
        }
        public boolean sameCredentials(Session other) {
            return sameIdentity(other) && Objects.equals(accessToken, other.accessToken)
                    && Objects.equals(refreshToken, other.refreshToken);
        }
    }

    public interface Store {
        Session current();
        boolean replace(Session expected, String accessToken, String refreshToken);
        boolean clear(Session expected);
    }
    public interface Transport { Response exchange(Session session) throws Exception; }
    public static final class Response {
        public final int status;
        public final String accessToken, refreshToken;
        public Response(int status, String accessToken, String refreshToken) {
            this.status = status;
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
        }
    }
    public enum State { READY, TEMPORARY_FAILURE, UNAUTHORIZED, SESSION_CHANGED }
    public static final class Result {
        public final State state;
        public final Session session;
        private Result(State state, Session session) { this.state = state; this.session = session; }
    }

    private Session lastTemporaryFailure;
    private long lastFailureNanos;

    public synchronized Result afterUnauthorized(Session failed, Store store, Transport transport) {
        Session current = store.current();
        if (!failed.sameIdentity(current)) return new Result(State.SESSION_CHANGED, null);
        if (!Objects.equals(failed.accessToken, current.accessToken) && current.accessToken != null) {
            return new Result(State.READY, current);
        }
        if (current.refreshToken == null || current.refreshToken.isEmpty()) {
            return new Result(store.clear(current) ? State.UNAUTHORIZED : State.SESSION_CHANGED, null);
        }
        // Concurrent callers share a transient failure; a later user retry can try again.
        if (current.sameCredentials(lastTemporaryFailure)
                && System.nanoTime() - lastFailureNanos < 2_000_000_000L) {
            return new Result(State.TEMPORARY_FAILURE, null);
        }
        try {
            Response response = transport.exchange(current);
            if (!current.sameCredentials(store.current())) return new Result(State.SESSION_CHANGED, null);
            if (response.status >= 200 && response.status < 300
                    && response.accessToken != null && !response.accessToken.isEmpty()) {
                if (!store.replace(current, response.accessToken, response.refreshToken)) {
                    return new Result(State.SESSION_CHANGED, null);
                }
                Session updated = store.current();
                return current.sameIdentity(updated) ? new Result(State.READY, updated)
                        : new Result(State.SESSION_CHANGED, null);
            }
            if (response.status == 401 || response.status == 403) {
                return new Result(store.clear(current) ? State.UNAUTHORIZED : State.SESSION_CHANGED, null);
            }
        } catch (Exception ignored) {
            if (!current.sameCredentials(store.current())) return new Result(State.SESSION_CHANGED, null);
        }
        lastTemporaryFailure = current;
        lastFailureNanos = System.nanoTime();
        return new Result(State.TEMPORARY_FAILURE, null);
    }
}
