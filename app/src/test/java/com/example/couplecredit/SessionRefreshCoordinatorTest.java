package com.example.couplecredit;

import com.example.couplecredit.api.SessionRefreshCoordinator;
import com.example.couplecredit.api.SessionRefreshCoordinator.*;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class SessionRefreshCoordinatorTest {
    private static class MemoryStore implements Store {
        Session session = new Session(1, "old-access", "refresh", "https://test.invalid");
        int clears;
        public synchronized Session current() { return session; }
        public synchronized boolean replace(Session expected, String access, String refresh) {
            if (!expected.sameCredentials(session)) return false;
            session = new Session(session.generation, access, refresh == null ? session.refreshToken : refresh, session.baseUrl);
            return true;
        }
        public synchronized boolean clear(Session expected) {
            if (!expected.sameCredentials(session)) return false;
            clears++;
            session = new Session(session.generation + 1, null, null, session.baseUrl);
            return true;
        }
        synchronized void changeAccount() { session = new Session(2, "other-access", "other-refresh", session.baseUrl); }
    }

    @Test public void simultaneousUnauthorizedRequestsShareOneRefreshIncludingRotation() throws Exception {
        SessionRefreshCoordinator coordinator = new SessionRefreshCoordinator();
        MemoryStore store = new MemoryStore(); Session failed = store.current();
        AtomicInteger calls = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch gate = new CountDownLatch(1);
        try {
            java.util.List<Future<Result>> results = new java.util.ArrayList<>();
            for (int i = 0; i < 8; i++) results.add(executor.submit(() -> {
                gate.await();
                return coordinator.afterUnauthorized(failed, store, s -> {
                    calls.incrementAndGet(); return new Response(200, "new-access", "rotated-refresh");
                });
            }));
            gate.countDown();
            for (Future<Result> result : results) assertEquals(State.READY, result.get(3, TimeUnit.SECONDS).state);
            assertEquals(1, calls.get());
        } finally { executor.shutdownNow(); }
    }

    @Test public void serverErrorsAndTimeoutDoNotLogOut() {
        for (boolean timeout : new boolean[]{false, true}) {
            SessionRefreshCoordinator coordinator = new SessionRefreshCoordinator();
            MemoryStore store = new MemoryStore(); Session before = store.current();
            AtomicInteger calls = new AtomicInteger();
            Transport transport = s -> { calls.incrementAndGet(); if (timeout) throw new java.io.IOException(); return new Response(503, null, null); };
            assertEquals(State.TEMPORARY_FAILURE, coordinator.afterUnauthorized(before, store, transport).state);
            assertEquals(State.TEMPORARY_FAILURE, coordinator.afterUnauthorized(before, store, transport).state);
            assertEquals(0, store.clears); assertSame(before, store.current()); assertEquals(1, calls.get());
        }
    }

    @Test public void definitiveRejectionClearsOnlyCurrentSession() {
        MemoryStore store = new MemoryStore(); Session before = store.current();
        assertEquals(State.UNAUTHORIZED, new SessionRefreshCoordinator().afterUnauthorized(before, store, s -> new Response(401, null, null)).state);
        assertEquals(1, store.clears);
    }

    @Test public void delayedSuccessOrRejectionCannotOverwriteNewLogin() {
        for (int status : new int[]{200, 401}) {
            MemoryStore store = new MemoryStore(); Session before = store.current();
            Result result = new SessionRefreshCoordinator().afterUnauthorized(before, store, s -> {
                store.changeAccount(); return new Response(status, "stale-access", null);
            });
            assertEquals(State.SESSION_CHANGED, result.state);
            assertEquals("other-access", store.current().accessToken); assertEquals(0, store.clears);
        }
    }

    @Test public void malformedSuccessRetainsLoginForRetry() {
        MemoryStore store = new MemoryStore();
        assertEquals(State.TEMPORARY_FAILURE, new SessionRefreshCoordinator().afterUnauthorized(store.current(), store, s -> new Response(200, null, null)).state);
        assertEquals(0, store.clears);
    }

    @Test public void accountSwitchImmediatelyAfterTokenSaveCannotRetryAsNewUser() {
        MemoryStore store = new MemoryStore() {
            @Override public synchronized boolean replace(Session expected, String access, String refresh) {
                boolean replaced = super.replace(expected, access, refresh);
                changeAccount();
                return replaced;
            }
        };
        Result result = new SessionRefreshCoordinator().afterUnauthorized(store.current(), store,
                s -> new Response(200, "renewed", null));
        assertEquals(State.SESSION_CHANGED, result.state);
        assertNull(result.session);
    }
}
