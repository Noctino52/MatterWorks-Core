package com.matterworks.core.ui.swing.debug;

import javax.swing.*;
import java.awt.*;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

public final class UiDebug {

    private UiDebug() {}

    // -------------------------
    // Tunables
    // -------------------------

    /** Logs a "slow event finished" message if a single dispatch takes >= this. */
    public static volatile long slowEdtEventMs = 40;

    /**
     * Samples the EDT stack WHILE an event is still running.
     * If an event runs longer than this threshold, we dump a live stack once for that event.
     */
    public static volatile long edtSlowSampleMs = 120;

    /** "Hard hang" threshold (prints full stack once per event). */
    public static volatile long edtHangMs = 1200;

    /** How many frames to print for live sampling. */
    public static volatile int edtSampleFrames = 18;

    /** Enables additional stack dump at end of slow event (usually not helpful). */
    public static volatile boolean slowEdtDumpStackAfter = false;

    /** How many frames to print for the "finished slow event" dump. */
    public static volatile int slowEdtStackFrames = 10;

    // -------------------------
    // Internal state
    // -------------------------

    private static volatile boolean installed = false;

    private static final AtomicLong lastEdtEventStartNs = new AtomicLong(0);
    private static final AtomicLong lastEdtEventEndNs = new AtomicLong(0);

    // Used to ensure we sample stack once per event (per start timestamp)
    private static final AtomicLong lastSampledEventStartNs = new AtomicLong(0);
    private static final AtomicLong lastHangReportedEventStartNs = new AtomicLong(0);

    private static ScheduledExecutorService monitorExec;

    // -------------------------
    // Install
    // -------------------------

    public static void install() {
        if (installed) return;
        installed = true;

        // 1) Timing EventQueue (end-of-event slow logs)
        Toolkit.getDefaultToolkit().getSystemEventQueue().push(new TimingEventQueue());

        // 2) RepaintManager instrumentation
        try {
            RepaintManager.setCurrentManager(new DebugRepaintManager());
            log("DebugRepaintManager installed.");
        } catch (Throwable t) {
            log("Failed to install DebugRepaintManager: " + t);
        }

        // 3) EDT monitor (live sampling + hang)
        monitorExec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mw-ui-edt-monitor");
            t.setDaemon(true);
            return t;
        });
        monitorExec.scheduleAtFixedRate(UiDebug::monitorEdt, 50, 50, TimeUnit.MILLISECONDS);

        log("UiDebug installed. slowEdtEventMs=" + slowEdtEventMs
                + " edtSlowSampleMs=" + edtSlowSampleMs
                + " edtHangMs=" + edtHangMs
                + " edtSampleFrames=" + edtSampleFrames);
    }

    // -------------------------
    // Public helpers (COMPAT)
    // -------------------------

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

    public static <T> T time(String name, Callable<T> call, int warnMs) {
        long t0 = System.nanoTime();
        try {
            return call.call();
        } catch (RuntimeException re) {
            log("EX in " + name + ": " + re);
            throw re;
        } catch (Exception e) {
            log("EX in " + name + ": " + e);
            throw new RuntimeException(e);
        } finally {
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            if (ms >= warnMs) {
                log("SLOW " + ms + "ms :: " + name + " [thread=" + Thread.currentThread().getName() + "]");
            }
        }
    }

    public static void time(String name, Runnable run, int warnMs) {
        long t0 = System.nanoTime();
        try {
            run.run();
        } catch (RuntimeException re) {
            log("EX in " + name + ": " + re);
            throw re;
        } catch (Throwable t) {
            log("EX in " + name + ": " + t);
            throw t;
        } finally {
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            if (ms >= warnMs) {
                log("SLOW " + ms + "ms :: " + name + " [thread=" + Thread.currentThread().getName() + "]");
            }
        }
    }

    /** Convenience overload for lambdas that don't throw checked exceptions. */
    public static <T> T time(String name, Supplier<T> supplier, int warnMs) {
        long t0 = System.nanoTime();
        try {
            return supplier.get();
        } catch (RuntimeException re) {
            log("EX in " + name + ": " + re);
            throw re;
        } finally {
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            if (ms >= warnMs) {
                log("SLOW " + ms + "ms :: " + name + " [thread=" + Thread.currentThread().getName() + "]");
            }
        }
    }

    // -------------------------
    // Timing EventQueue (END-OF-EVENT logging)
    // -------------------------

    private static final class TimingEventQueue extends EventQueue {

        @Override
        protected void dispatchEvent(AWTEvent event) {
            long start = System.nanoTime();
            lastEdtEventStartNs.set(start);
            // mark "no end yet" (end < start means: still running)
            lastEdtEventEndNs.set(start - 1);

            try {
                super.dispatchEvent(event);
            } finally {
                long end = System.nanoTime();
                lastEdtEventEndNs.set(end);

                long ms = (end - start) / 1_000_000L;
                if (ms < slowEdtEventMs) return;

                Object src = event.getSource();
                String srcName = (src != null ? safeClassName(src) : "null");

                String runnableInfo = "";
                if (event instanceof java.awt.event.InvocationEvent ie) {
                    runnableInfo = " runnable=" + safeInvocationRunnableName(ie);
                }

                String summary = formatEventSummary(event);

                log("EDT SLOW EVENT " + ms + "ms :: " + event.getClass().getName()
                        + " from " + srcName
                        + runnableInfo
                        + (summary.isEmpty() ? "" : " " + summary));

                // NOTE: this is AFTER the event completed.
                if (slowEdtDumpStackAfter) {
                    dumpCurrentThreadStackShort("EDT slow event stack (AFTER)", slowEdtStackFrames);
                }
            }
        }
    }

    private static String safeClassName(Object o) {
        try {
            return o.getClass().getName();
        } catch (Throwable t) {
            return "unknown";
        }
    }

    private static String safeInvocationRunnableName(java.awt.event.InvocationEvent ie) {
        try {
            Object candidate = tryGetField(ie, java.awt.event.InvocationEvent.class, "runnable");
            if (candidate == null) candidate = tryGetField(ie, java.awt.event.InvocationEvent.class, "action");
            if (candidate == null) return "unknown";
            return safeClassName(candidate);
        } catch (Throwable t) {
            return "unknown";
        }
    }

    private static Object tryGetField(Object target, Class<?> owner, String fieldName) {
        try {
            Field f = owner.getDeclaredField(fieldName);
            f.setAccessible(true);
            return f.get(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String formatEventSummary(AWTEvent event) {
        try {
            if (event instanceof java.awt.event.MouseEvent me) {
                return "(mouse id=" + me.getID() + " x=" + me.getX() + " y=" + me.getY() + " btn=" + me.getButton() + ")";
            }
            if (event instanceof java.awt.event.KeyEvent ke) {
                return "(key id=" + ke.getID() + " code=" + ke.getKeyCode() + ")";
            }
            if (event instanceof java.awt.event.ActionEvent ae) {
                Object src = ae.getSource();
                String s = (src != null ? safeClassName(src) : "null");
                return "(action cmd=" + ae.getActionCommand() + " src=" + s + ")";
            }
            return "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    // -------------------------
    // LIVE EDT monitoring (the key)
    // -------------------------

    private static void monitorEdt() {
        long start = lastEdtEventStartNs.get();
        if (start == 0) return;

        long end = lastEdtEventEndNs.get();

        // If end < start, we are INSIDE an event dispatch right now.
        boolean inEvent = end < start;
        if (!inEvent) return;

        long now = System.nanoTime();
        long stuckMs = (now - start) / 1_000_000L;

        Thread edt = findEdtThread();
        if (edt == null) return;

        // 1) Slow sampling: once per event when it crosses edtSlowSampleMs
        if (stuckMs >= edtSlowSampleMs) {
            long prev = lastSampledEventStartNs.get();
            if (prev != start && lastSampledEventStartNs.compareAndSet(prev, start)) {
                log("EDT LIVE SLOW SAMPLE @" + stuckMs + "ms (event still running). EDT stack:");
                dumpThreadStackShort(edt, edtSampleFrames);
            }
        }

        // 2) Hard hang: once per event when it crosses edtHangMs
        if (stuckMs >= edtHangMs) {
            long prev = lastHangReportedEventStartNs.get();
            if (prev != start && lastHangReportedEventStartNs.compareAndSet(prev, start)) {
                log("EDT HANG >" + edtHangMs + "ms detected (" + stuckMs + "ms). FULL EDT stack:");
                for (StackTraceElement el : edt.getStackTrace()) {
                    log("  at " + el);
                }
            }
        }
    }

    private static Thread findEdtThread() {
        for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
            Thread t = e.getKey();
            String name = t.getName();
            if (name != null && name.startsWith("AWT-EventQueue")) return t;
        }
        return null;
    }

    // -------------------------
    // Stack dump helpers
    // -------------------------

    private static void dumpCurrentThreadStackShort(String title, int maxFrames) {
        log(title + ":");
        StackTraceElement[] st = Thread.currentThread().getStackTrace();
        dumpStackFrames(st, maxFrames);
    }

    private static void dumpThreadStackShort(Thread t, int maxFrames) {
        StackTraceElement[] st = t.getStackTrace();
        dumpStackFrames(st, maxFrames);
    }

    private static void dumpStackFrames(StackTraceElement[] st, int maxFrames) {
        int printed = 0;

        // 1) Prefer application frames
        for (StackTraceElement el : st) {
            String cn = el.getClassName();
            if (cn.startsWith("com.matterworks.")
                    && !cn.startsWith("com.matterworks.core.ui.swing.debug.UiDebug")) {
                log("  at " + cn + "." + el.getMethodName() + ":" + el.getLineNumber());
                printed++;
                if (printed >= maxFrames) return;
            }
        }

        // 2) Fallback: print some useful frames anyway
        for (StackTraceElement el : st) {
            String cn = el.getClassName();
            if (cn.startsWith("com.matterworks.core.ui.swing.debug.UiDebug")) continue;
            if (cn.startsWith("java.lang.Thread")) continue;

            log("  at " + el);
            printed++;
            if (printed >= maxFrames) return;
        }
    }

    // -------------------------
    // Repaint storm detector
    // -------------------------

    private static final class DebugRepaintManager extends RepaintManager {

        private static final long LOG_MIN_INTERVAL_NS = 500_000_000L; // 500ms
        private static final int BURST_THRESHOLD = 80;               // dirty calls within 1s

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

            if (now - windowStartNs > 1_000_000_000L) {
                windowStartNs = now;
                windowCount = 0;
            }
            windowCount++;

            if (windowCount < BURST_THRESHOLD) return;
            if (now - lastLogNs < LOG_MIN_INTERVAL_NS) return;
            lastLogNs = now;

            String comp = (c != null ? safeClassName(c) : "null");

            StackTraceElement culprit = findCulprit(Thread.currentThread().getStackTrace());
            if (culprit != null) {
                log("REPAINT STORM (" + windowCount + "/s) kind=" + kind + " comp=" + comp
                        + " culprit=" + culprit.getClassName() + "." + culprit.getMethodName()
                        + ":" + culprit.getLineNumber());
            } else {
                log("REPAINT STORM (" + windowCount + "/s) kind=" + kind + " comp=" + comp + " culprit=unknown");
            }
        }
    }

    private static StackTraceElement findCulprit(StackTraceElement[] st) {
        for (StackTraceElement el : st) {
            String cn = el.getClassName();
            if (cn.startsWith("com.matterworks.")
                    && !cn.startsWith("com.matterworks.core.ui.swing.debug.UiDebug")) {
                return el;
            }
        }
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
}