package org.cef;

/** Narrow adapter for JCEF's protected, synchronized browser snapshot; no reflective field access. */
public final class YxiClientLifecycle {
    private YxiClientLifecycle() {}

    /** Call only after client.dispose(). The snapshot's monitor also waits for cleanupBrowser to finish. */
    public static boolean isDrained(CefClient client) {
        return client.getAllBrowser().length == 0;
    }
}
