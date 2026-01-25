package com.matterworks.core.domain.telemetry.production;

import com.matterworks.core.domain.matter.MatterPayload;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bucket-based ring buffer.
 * - bucketSizeSeconds: 5 seconds
 * - retention: 10 minutes (covers 1m/5m/10m)
 *
 * PERFORMANCE NOTES:
 * - record*() is in the simulation hot path -> must be allocation-light.
 * - Avoid Instant allocations: use clock.millis() / 1000
 * - Avoid Long boxing: use MutableLong counters in buckets
 * - Avoid lambdas in hot path (computeIfAbsent/merge with method refs)
 *
 * THREADING NOTES:
 * - UI may call getSnapshot() concurrently with simulation record*().
 * - We synchronize per-player buffer to avoid ConcurrentModificationException.
 */
public final class InMemoryProductionTelemetry implements ProductionTelemetry {

    private static final int BUCKET_SIZE_SECONDS = 5;
    private static final int RETENTION_SECONDS = 10 * 60;
    private static final int BUCKET_COUNT = RETENTION_SECONDS / BUCKET_SIZE_SECONDS; // 120

    private final Clock clock;
    private final ConcurrentHashMap<UUID, PlayerBuffer> buffers = new ConcurrentHashMap<>();

    public InMemoryProductionTelemetry() {
        this(Clock.systemUTC());
    }

    public InMemoryProductionTelemetry(Clock clock) {
        this.clock = (clock != null) ? clock : Clock.systemUTC();
    }

    @Override
    public void recordProduced(UUID playerId, MatterPayload payload, long quantity) {
        if (playerId == null || payload == null || quantity <= 0) return;
        getOrCreate(playerId).recordProduced(nowEpochSeconds(), payload, quantity);
    }

    @Override
    public void recordConsumed(UUID playerId, MatterPayload payload, long quantity) {
        if (playerId == null || payload == null || quantity <= 0) return;
        getOrCreate(playerId).recordConsumed(nowEpochSeconds(), payload, quantity);
    }

    @Override
    public void recordSold(UUID playerId, MatterPayload payload, long quantity, double moneyEarned) {
        if (playerId == null || payload == null || quantity <= 0) return;
        double money = Math.max(0.0, moneyEarned);
        getOrCreate(playerId).recordSold(nowEpochSeconds(), payload, quantity, money);
    }

    @Override
    public ProductionStatsSnapshot getSnapshot(UUID playerId, ProductionTimeWindow window) {
        ProductionTimeWindow w = (window != null) ? window : ProductionTimeWindow.ONE_MINUTE;
        if (playerId == null) {
            return emptySnapshot(w);
        }

        PlayerBuffer buffer = buffers.get(playerId);
        if (buffer == null) {
            return emptySnapshot(w);
        }

        return buffer.snapshot(nowEpochSeconds(), w);
    }

    private ProductionStatsSnapshot emptySnapshot(ProductionTimeWindow window) {
        return new ProductionStatsSnapshot(
                window,
                Map.of(), Map.of(),
                Map.of(), Map.of(),
                Map.of(), Map.of(),
                0L, 0L, 0L, 0.0
        );
    }

    private PlayerBuffer getOrCreate(UUID playerId) {
        // lambda here is not in the hot path (only first time per player)
        return buffers.computeIfAbsent(playerId, _ignored -> new PlayerBuffer());
    }

    /**
     * Allocation-free epoch seconds:
     * - Clock.instant() allocates an Instant, too heavy in hot path.
     */
    private long nowEpochSeconds() {
        return clock.millis() / 1000L;
    }

    private static int windowBuckets(ProductionTimeWindow window) {
        int seconds = window.getSeconds();
        return Math.max(1, seconds / BUCKET_SIZE_SECONDS);
    }

    private static int bucketIndex(long bucketStartSeconds) {
        long bucket = (bucketStartSeconds / BUCKET_SIZE_SECONDS);
        return (int) (bucket % BUCKET_COUNT);
    }

    private static long bucketStartSeconds(long epochSeconds) {
        return (epochSeconds / BUCKET_SIZE_SECONDS) * BUCKET_SIZE_SECONDS;
    }

    /**
     * Minimal mutable long to avoid boxing.
     */
    private static final class MutableLong {
        long v;
        MutableLong(long v) { this.v = v; }
        void add(long x) { this.v += x; }
        long get() { return v; }
    }

    private static final class PlayerBuffer {
        private final Bucket[] buckets = new Bucket[BUCKET_COUNT];

        PlayerBuffer() {
            for (int i = 0; i < buckets.length; i++) buckets[i] = new Bucket();
        }

        /**
         * Hot path: synchronized per-player to avoid snapshot() iterating while we mutate maps.
         */
        synchronized void recordProduced(long nowSec, MatterPayload payload, long qty) {
            Bucket b = rotateAndGetLocked(nowSec);
            inc(b.producedByColor, MatterTelemetryKeys.colorKey(payload), qty);
            inc(b.producedByMatter, MatterTelemetryKeys.matterKey(payload), qty);
        }

        synchronized void recordConsumed(long nowSec, MatterPayload payload, long qty) {
            Bucket b = rotateAndGetLocked(nowSec);
            inc(b.consumedByColor, MatterTelemetryKeys.colorKey(payload), qty);
            inc(b.consumedByMatter, MatterTelemetryKeys.matterKey(payload), qty);
        }

        synchronized void recordSold(long nowSec, MatterPayload payload, long qty, double money) {
            Bucket b = rotateAndGetLocked(nowSec);

            String ck = MatterTelemetryKeys.colorKey(payload);
            String mk = MatterTelemetryKeys.matterKey(payload);

            addSold(b.soldByColor, ck, qty, money);
            addSold(b.soldByMatter, mk, qty, money);
        }

        /**
         * Snapshot is called by UI. Keep it synchronized per-player to avoid CME and keep data consistent.
         */
        synchronized ProductionStatsSnapshot snapshot(long nowSec, ProductionTimeWindow window) {
            int wBuckets = windowBuckets(window);
            long windowStartSec = nowSec - window.getSeconds();

            Map<String, Long> producedColor = new HashMap<>();
            Map<String, Long> producedMatter = new HashMap<>();

            Map<String, Long> consumedColor = new HashMap<>();
            Map<String, Long> consumedMatter = new HashMap<>();

            Map<String, SoldStats> soldColor = new HashMap<>();
            Map<String, SoldStats> soldMatter = new HashMap<>();

            long totalProduced = 0L;
            long totalConsumed = 0L;

            long cursor = bucketStartSeconds(nowSec);
            long minCursor = bucketStartSeconds(windowStartSec);

            for (int i = 0; i < wBuckets; i++) {
                if (cursor < minCursor) break;

                int idx = bucketIndex(cursor);
                Bucket b = buckets[idx];

                if (b.bucketStartSeconds == cursor) {
                    // Produced
                    for (Map.Entry<String, MutableLong> e : b.producedByColor.entrySet()) {
                        long v = e.getValue().get();
                        if (v <= 0) continue;
                        producedColor.put(e.getKey(), producedColor.getOrDefault(e.getKey(), 0L) + v);
                        totalProduced += v;
                    }
                    for (Map.Entry<String, MutableLong> e : b.producedByMatter.entrySet()) {
                        long v = e.getValue().get();
                        if (v <= 0) continue;
                        producedMatter.put(e.getKey(), producedMatter.getOrDefault(e.getKey(), 0L) + v);
                    }

                    // Consumed
                    for (Map.Entry<String, MutableLong> e : b.consumedByColor.entrySet()) {
                        long v = e.getValue().get();
                        if (v <= 0) continue;
                        consumedColor.put(e.getKey(), consumedColor.getOrDefault(e.getKey(), 0L) + v);
                        totalConsumed += v;
                    }
                    for (Map.Entry<String, MutableLong> e : b.consumedByMatter.entrySet()) {
                        long v = e.getValue().get();
                        if (v <= 0) continue;
                        consumedMatter.put(e.getKey(), consumedMatter.getOrDefault(e.getKey(), 0L) + v);
                    }

                    // Sold
                    for (Map.Entry<String, SoldStats> e : b.soldByColor.entrySet()) {
                        SoldStats s = e.getValue();
                        if (s == null || s.getQuantity() <= 0) continue;
                        SoldStats acc = soldColor.get(e.getKey());
                        if (acc == null) {
                            acc = new SoldStats();
                            soldColor.put(e.getKey(), acc);
                        }
                        acc.add(s.getQuantity(), s.getMoneyEarned());
                    }
                    for (Map.Entry<String, SoldStats> e : b.soldByMatter.entrySet()) {
                        SoldStats s = e.getValue();
                        if (s == null || s.getQuantity() <= 0) continue;
                        SoldStats acc = soldMatter.get(e.getKey());
                        if (acc == null) {
                            acc = new SoldStats();
                            soldMatter.put(e.getKey(), acc);
                        }
                        acc.add(s.getQuantity(), s.getMoneyEarned());
                    }
                }

                cursor -= BUCKET_SIZE_SECONDS;
            }

            long totalSoldQty = 0L;
            double totalMoney = 0.0;
            for (SoldStats s : soldMatter.values()) {
                totalSoldQty += s.getQuantity();
                totalMoney += s.getMoneyEarned();
            }

            return new ProductionStatsSnapshot(
                    window,
                    producedColor, producedMatter,
                    consumedColor, consumedMatter,
                    soldColor, soldMatter,
                    totalProduced,
                    totalConsumed,
                    totalSoldQty,
                    totalMoney
            );
        }

        /**
         * Must be called under "synchronized(this)".
         */
        private Bucket rotateAndGetLocked(long nowSec) {
            long start = bucketStartSeconds(nowSec);
            int idx = bucketIndex(start);
            Bucket b = buckets[idx];

            if (b.bucketStartSeconds != start) {
                b.bucketStartSeconds = start;

                b.producedByColor.clear();
                b.producedByMatter.clear();

                b.consumedByColor.clear();
                b.consumedByMatter.clear();

                b.soldByColor.clear();
                b.soldByMatter.clear();
            }
            return b;
        }

        private static void inc(Map<String, MutableLong> map, String key, long qty) {
            if (qty <= 0) return;
            MutableLong c = map.get(key);
            if (c == null) {
                map.put(key, new MutableLong(qty));
            } else {
                c.add(qty);
            }
        }

        private static void addSold(Map<String, SoldStats> map, String key, long qty, double money) {
            SoldStats s = map.get(key);
            if (s == null) {
                s = new SoldStats();
                map.put(key, s);
            }
            s.add(qty, money);
        }
    }

    private static final class Bucket {
        long bucketStartSeconds = Long.MIN_VALUE;

        final Map<String, MutableLong> producedByColor = new HashMap<>();
        final Map<String, MutableLong> producedByMatter = new HashMap<>();

        final Map<String, MutableLong> consumedByColor = new HashMap<>();
        final Map<String, MutableLong> consumedByMatter = new HashMap<>();

        final Map<String, SoldStats> soldByColor = new HashMap<>();
        final Map<String, SoldStats> soldByMatter = new HashMap<>();
    }
}
