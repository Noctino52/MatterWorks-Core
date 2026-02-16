package com.matterworks.core.ui.swing.debug;

import javax.swing.*;
import java.awt.*;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public final class UiDebug {

    private UiDebug() {}

    // Thresholds (tune if needed)
    public static volatile long slowEdtEventMs = 40;     // logs slow AWT event dispatch
    public static volatile long edtHangMs = 1200;        // logs EDT hang with stacktrace
    public static volatile long slowDaoMs = 25;          // logs slow DB calls (if wrapped)

    private static volatile boolean installed = false;

    private static final AtomicLong lastEdtEventStartNs = new AtomicLong(0);
    private static final AtomicLong lastEdtEventEndNs = new AtomicLong(0);

    private static ScheduledExecutorService hangMonitor;

    public static void install() {
        if (installed) return;
        installed = true;

        // 1) EventQueue timing
        Toolkit.getDefaultToolkit().getSystemEventQueue().push(new TimingEventQueue());

        // 2) RepaintManager instrumentation (tells us who calls repaint/revalidate)
        try {
            RepaintManager.setCurrentManager(new DebugRepaintManager());
            log("DebugRepaintManager installed.");
        } catch (Throwable t) {
            log("Failed to install DebugRepaintManager: " + t);
        }

        hangMonitor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mw-ui-hang-monitor");
            t.setDaemon(true);
            return t;
        });

        hangMonitor.scheduleAtFixedRate(UiDebug::checkEdtHang, 200, 200, TimeUnit.MILLISECONDS);

        log("UiDebug installed. slowEdtEventMs=" + slowEdtEventMs + " edtHangMs=" + edtHangMs + " slowDaoMs=" + slowDaoMs);
    }

    private static final class DebugRepaintManager extends RepaintManager {

        // Rate limit logs to avoid flooding
        private static final long LOG_MIN_INTERVAL_NS = 500_000_000L; // 500ms
        private static final int BURST_THRESHOLD = 80;               // dirty calls within window

        private long windowStartNs = System.nanoTime();
        private int windowCount = 0;

        private long lastLogNs = 0;

        @Override
        public synchronized void addDirtyRegion(JComponent c, int x, int y, int w, int h) {
            super.addDirtyRegion(c, x, y, w, h);
            onDirty("addDirtyRegion", c);
        }

        @Override
        public synchronized void markCompletelyDirty(JComponent aComponent) {
            super.markCompletelyDirty(aComponent);
            onDirty("markCompletelyDirty", aComponent);
        }

        private void onDirty(String kind, JComponent c) {
            long now = System.nanoTime();

            // sliding window 1s
            if (now - windowStartNs > 1_000_000_000L) {
                windowStartNs = now;
                windowCount = 0;
            }
            windowCount++;

            if (windowCount < BURST_THRESHOLD) return;

            // log rate-limit
            if (now - lastLogNs < LOG_MIN_INTERVAL_NS) return;
            lastLogNs = now;

            String comp = "null";
            if (c != null) {
                try {
                    comp = c.getClass().getName();
                } catch (Throwable ignored) {
                    comp = "unknown";
                }
            }

            // Grab stack trace (caller thread; often EDT)
            StackTraceElement[] st;
            try {
                st = Thread.currentThread().getStackTrace();
            } catch (Throwable t) {
                st = new StackTraceElement[0];
            }

            StackTraceElement culprit = findCulprit(st);
            if (culprit != null) {
                log("REPAINT STORM (" + windowCount + "/s) kind=" + kind + " comp=" + comp
                        + " culprit=" + culprit.getClassName() + "." + culprit.getMethodName()
                        + ":" + culprit.getLineNumber());
            } else {
                log("REPAINT STORM (" + windowCount + "/s) kind=" + kind + " comp=" + comp
                        + " culprit=unknown");
            }

            // Extra: when FactoryPanel is involved, print a short stack once per burst tick
            if (c != null && c.getClass().getName().equals("com.matterworks.core.ui.swing.factory.FactoryPanel")) {
                log("REPAINT STORM stack (FactoryPanel) top frames:");
                int printed = 0;
                for (StackTraceElement el : st) {
                    String cn = el.getClassName();
                    if (cn.startsWith("com.matterworks.") && !cn.startsWith("com.matterworks.core.ui.swing.debug.UiDebug")) {
                        log("  at " + cn + "." + el.getMethodName() + ":" + el.getLineNumber());
                        printed++;
                        if (printed >= 8) break;
                    }
                }
                if (printed == 0) {
                    // fallback: show first non-swing frames
                    for (StackTraceElement el : st) {
                        String cn = el.getClassName();
                        if (!cn.startsWith("java.") && !cn.startsWith("javax.") && !cn.startsWith("sun.")
                                && !cn.startsWith("com.sun.") && !cn.startsWith("jdk.")
                                && !cn.startsWith("com.matterworks.core.ui.swing.debug.UiDebug")) {
                            log("  at " + cn + "." + el.getMethodName() + ":" + el.getLineNumber());
                            printed++;
                            if (printed >= 8) break;
                        }
                    }
                }
            }
        }
    }

    private static StackTraceElement findCulprit(StackTraceElement[] st) {
        // Prefer: first app-frame that is NOT UiDebug
        for (StackTraceElement el : st) {
            String cn = el.getClassName();
            if (cn.startsWith("com.matterworks.")
                    && !cn.startsWith("com.matterworks.core.ui.swing.debug.UiDebug")) {
                return el;
            }
        }

        // Fallback: first non-JDK/non-Swing frame (still excluding UiDebug)
        for (StackTraceElement el : st) {
            String cn = el.getClassName();
            if (cn.startsWith("com.matterworks.core.ui.swing.debug.UiDebug")) continue;
            if (cn.startsWith("java.") || cn.startsWith("javax.") || cn.startsWith("sun.")
                    || cn.startsWith("com.sun.") || cn.startsWith("jdk.")) {
                continue;
            }
            return el;
        }

        return null;
    }

    public static void log(String msg) {
        System.out.println("[UI-DBG] " + msg);
    }

    public static void logThread(String msg) {
        log(msg + " [thread=" + Thread.currentThread().getName() + "]");
    }

    public static void dumpStack(String title) {
        log(title + " stacktrace dump:");
        for (StackTraceElement el : Thread.currentThread().getStackTrace()) {
            log("  at " + el);
        }
    }

    public static <T> T time(String name, Callable<T> call, long warnMs) {
        long t0 = System.nanoTime();
        try {
            return call.call();
        } catch (Exception e) {
            log("EX in " + name + ": " + e);
            throw new RuntimeException(e);
        } finally {
            long ms = (System.nanoTime() - t0) / 1_000_000;
            if (ms >= warnMs) log("SLOW " + ms + "ms :: " + name + " [thread=" + Thread.currentThread().getName() + "]");
        }
    }

    public static void time(String name, Runnable run, long warnMs) {
        long t0 = System.nanoTime();
        try {
            run.run();
        } catch (Throwable t) {
            log("EX in " + name + ": " + t);
            throw t;
        } finally {
            long ms = (System.nanoTime() - t0) / 1_000_000;
            if (ms >= warnMs) log("SLOW " + ms + "ms :: " + name + " [thread=" + Thread.currentThread().getName() + "]");
        }
    }

    private static final class TimingEventQueue extends EventQueue {
        @Override
        protected void dispatchEvent(AWTEvent event) {
            long start = System.nanoTime();
            lastEdtEventStartNs.set(start);

            try {
                super.dispatchEvent(event);
            } finally {
                long end = System.nanoTime();
                lastEdtEventEndNs.set(end);

                long ms = (end - start) / 1_000_000;
                if (ms >= slowEdtEventMs) {
                    Object src = event.getSource();
                    String srcName = (src != null ? src.getClass().getName() : "null");

                    String extra = "";

                    // If it's an InvocationEvent, try to discover the runnable via reflection
                    if (event instanceof java.awt.event.InvocationEvent ie) {
                        String runnableName = "unknown";
                        try {
                            Object candidate = null;

                            // JDK often keeps it in a private field named "runnable"
                            try {
                                java.lang.reflect.Field f = java.awt.event.InvocationEvent.class.getDeclaredField("runnable");
                                f.setAccessible(true);
                                candidate = f.get(ie);
                            } catch (NoSuchFieldException ignored) {
                                // Some JDKs use different internals
                            }

                            // Fallback: sometimes there's an "action" field (rare), try it too
                            if (candidate == null) {
                                try {
                                    java.lang.reflect.Field f = java.awt.event.InvocationEvent.class.getDeclaredField("action");
                                    f.setAccessible(true);
                                    candidate = f.get(ie);
                                } catch (NoSuchFieldException ignored) {
                                    // ignore
                                }
                            }

                            if (candidate instanceof Runnable r) {
                                runnableName = r.getClass().getName();
                            } else if (candidate != null) {
                                runnableName = candidate.getClass().getName();
                            }
                        } catch (Throwable ignored) {
                            // keep runnableName as "unknown"
                        }

                        extra = " runnable=" + runnableName;
                    }

                    log("EDT SLOW EVENT " + ms + "ms :: " + event.getClass().getName()
                            + " from " + srcName + extra);
                }
            }
        }
    }

    private static void checkEdtHang() {
        long start = lastEdtEventStartNs.get();
        if (start == 0) return;

        long end = lastEdtEventEndNs.get();
        boolean inEvent = end < start;
        if (!inEvent) return;

        long now = System.nanoTime();
        long stuckMs = (now - start) / 1_000_000;
        if (stuckMs < edtHangMs) return;

        Thread edt = findEdtThread();
        if (edt == null) {
            log("EDT HANG >" + edtHangMs + "ms detected (" + stuckMs + "ms) but EDT thread not found.");
            // prevent spam
            lastEdtEventStartNs.set(System.nanoTime());
            return;
        }

        log("EDT HANG >" + edtHangMs + "ms detected (" + stuckMs + "ms). EDT stack:");
        for (StackTraceElement el : edt.getStackTrace()) {
            log("  at " + el);
        }

        // prevent spam
        lastEdtEventStartNs.set(System.nanoTime());
    }

    private static Thread findEdtThread() {
        for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
            Thread t = e.getKey();
            String name = t.getName();
            if (name != null && name.startsWith("AWT-EventQueue")) return t;
        }
        return null;
    }
}
