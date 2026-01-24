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
        this.clock = clock;
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
        getOrCreate(playerId).recordSold(nowEpochSeconds(), payload, quantity, Math.max(0.0, moneyEarned));
    }

    @Override
    public ProductionStatsSnapshot getSnapshot(UUID playerId, ProductionTimeWindow window) {
        if (playerId == null || window == null) {
            return emptySnapshot(window != null ? window : ProductionTimeWindow.ONE_MINUTE);
        }

        PlayerBuffer buffer = buffers.get(playerId);
        if (buffer == null) {
            return emptySnapshot(window);
        }

        return buffer.snapshot(nowEpochSeconds(), window);
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
        return buffers.computeIfAbsent(playerId, _ignored -> new PlayerBuffer());
    }

    /**
     * Allocation-free epoch seconds:
     * - Clock.instant() allocates an Instant, which is too heavy in hot path.
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
     * Minimal mutable long to avoid boxing in hot path.
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

        void recordProduced(long nowSec, MatterPayload payload, long qty) {
            Bucket b = rotateAndGet(nowSec);
            inc(b.producedByColor, MatterTelemetryKeys.colorKey(payload), qty);
            inc(b.producedByMatter, MatterTelemetryKeys.matterKey(payload), qty);
        }

        void recordConsumed(long nowSec, MatterPayload payload, long qty) {
            Bucket b = rotateAndGet(nowSec);
            inc(b.consumedByColor, MatterTelemetryKeys.colorKey(payload), qty);
            inc(b.consumedByMatter, MatterTelemetryKeys.matterKey(payload), qty);
        }

        void recordSold(long nowSec, MatterPayload payload, long qty, double money) {
            Bucket b = rotateAndGet(nowSec);

            String ck = MatterTelemetryKeys.colorKey(payload);
            String mk = MatterTelemetryKeys.matterKey(payload);

            b.soldByColor.computeIfAbsent(ck, _ignored -> new SoldStats()).add(qty, money);
            b.soldByMatter.computeIfAbsent(mk, _ignored -> new SoldStats()).add(qty, money);
        }

        ProductionStatsSnapshot snapshot(long nowSec, ProductionTimeWindow window) {
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
                    for (var e : b.producedByColor.entrySet()) {
                        long v = e.getValue().get();
                        if (v <= 0) continue;
                        producedColor.merge(e.getKey(), v, Long::sum);
                        totalProduced += v;
                    }
                    for (var e : b.producedByMatter.entrySet()) {
                        long v = e.getValue().get();
                        if (v <= 0) continue;
                        producedMatter.merge(e.getKey(), v, Long::sum);
                    }

                    // Consumed
                    for (var e : b.consumedByColor.entrySet()) {
                        long v = e.getValue().get();
                        if (v <= 0) continue;
                        consumedColor.merge(e.getKey(), v, Long::sum);
                        totalConsumed += v;
                    }
                    for (var e : b.consumedByMatter.entrySet()) {
                        long v = e.getValue().get();
                        if (v <= 0) continue;
                        consumedMatter.merge(e.getKey(), v, Long::sum);
                    }

                    // Sold
                    for (var e : b.soldByColor.entrySet()) {
                        SoldStats s = e.getValue();
                        if (s == null || s.getQuantity() <= 0) continue;
                        soldColor.computeIfAbsent(e.getKey(), _ignored -> new SoldStats())
                                .add(s.getQuantity(), s.getMoneyEarned());
                    }
                    for (var e : b.soldByMatter.entrySet()) {
                        SoldStats s = e.getValue();
                        if (s == null || s.getQuantity() <= 0) continue;
                        soldMatter.computeIfAbsent(e.getKey(), _ignored -> new SoldStats())
                                .add(s.getQuantity(), s.getMoneyEarned());
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
         * Rotates the ring buffer bucket for current time.
         * Synchronized per-player; hot path must remain small.
         */
        private synchronized Bucket rotateAndGet(long nowSec) {
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
                c = new MutableLong(qty);
                map.put(key, c);
            } else {
                c.add(qty);
            }
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
