package com.example.couplecredit.utils;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class DataRefreshBus {

    public interface Listener {
        void onDataRefresh();
    }

    private static final List<Listener> listeners = new CopyOnWriteArrayList<>();

    public static void subscribe(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    public static void unsubscribe(Listener l) {
        listeners.remove(l);
    }

    public static void refreshAll() {
        for (Listener l : listeners) {
            try {
                l.onDataRefresh();
            } catch (Exception ignored) {
            }
        }
    }
}
