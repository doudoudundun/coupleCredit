package com.example.couplecredit.utils;

import java.util.ArrayList;
import java.util.List;

public class DataRefreshBus {

    public interface Listener {
        void onDataRefresh();
    }

    private static final List<Listener> listeners = new ArrayList<>();

    public static void subscribe(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    public static void unsubscribe(Listener l) {
        listeners.remove(l);
    }

    public static void refreshAll() {
        for (Listener l : new ArrayList<>(listeners)) {
            l.onDataRefresh();
        }
    }
}
