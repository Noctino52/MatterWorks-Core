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

        if (playerId == null) return emptySnapshot(w);

        PlayerBuffer buffer = buffers.get(playerId);
        if (buffer == null) return emptySnapshot(w);

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

    private static int bucketIndex(long bucketStartSeconds) {
        long bucket = (bucketStartSeconds / BUCKET_SIZE_SECONDS);
        return (int) (bucket % BUCKET_COUNT);
    }

    private static long bucketStartSeconds(long epochSeconds) {
        return (epochSeconds / BUCKET_SIZE_SECONDS) * BUCKET_SIZE_SECONDS;
    }

    private static double overlapFraction(long bucketStart, long windowStart, long windowEndExclusive) {
        long bucketEnd = bucketStart + BUCKET_SIZE_SECONDS;

        long a = Math.max(bucketStart, windowStart);
        long b = Math.min(bucketEnd, windowEndExclusive);

        long overlap = b - a;
        if (overlap <= 0) return 0.0;

        if (overlap >= BUCKET_SIZE_SECONDS) return 1.0;
        return overlap / (double) BUCKET_SIZE_SECONDS;
    }

    private static void addScaledLongMap(Map<String, MutableLong> src, Map<String, Double> dst, double factor) {
        if (factor <= 0.0) return;
        for (Map.Entry<String, MutableLong> e : src.entrySet()) {
            long v = e.getValue().get();
            if (v <= 0) continue;
            dst.put(e.getKey(), dst.getOrDefault(e.getKey(), 0.0) + (v * factor));
        }
    }

    private static void addScaledSoldMap(Map<String, SoldStats> src, Map<String, SoldAcc> dst, double factor) {
        if (factor <= 0.0) return;
        for (Map.Entry<String, SoldStats> e : src.entrySet()) {
            SoldStats s = e.getValue();
            if (s == null) continue;

            long q = s.getQuantity();
            double m = s.getMoneyEarned();
            if (q <= 0 && m <= 0.0) continue;

            SoldAcc acc = dst.get(e.getKey());
            if (acc == null) {
                acc = new SoldAcc();
                dst.put(e.getKey(), acc);
            }
            acc.qty += q * factor;
            acc.money += m * factor;
        }
    }

    private static Map<String, Long> toRoundedLongMap(Map<String, Double> src) {
        Map<String, Long> out = new HashMap<>();
        for (Map.Entry<String, Double> e : src.entrySet()) {
            double d = e.getValue();
            if (d <= 0.0) continue;
            long v = Math.round(d);
            if (v > 0) out.put(e.getKey(), v);
        }
        return out;
    }

    private static Map<String, SoldStats> toRoundedSoldMap(Map<String, SoldAcc> src) {
        Map<String, SoldStats> out = new HashMap<>();
        for (Map.Entry<String, SoldAcc> e : src.entrySet()) {
            SoldAcc a = e.getValue();
            if (a == null) continue;

            long q = Math.round(a.qty);
            double m = a.money;

            if (q <= 0 && m <= 0.0) continue;
            out.put(e.getKey(), new SoldStats(Math.max(0L, q), Math.max(0.0, m)));
        }
        return out;
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

    private static final class SoldAcc {
        double qty;
        double money;
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
         *
         * IMPORTANT:
         * We compute "per window" using partial overlap on the first/last bucket,
         * otherwise a 60s window with 5s buckets may systematically under/over report
         * depending on alignment.
         */
        synchronized ProductionStatsSnapshot snapshot(long nowSec, ProductionTimeWindow window) {
            long windowEnd = nowSec; // exclusive-ish at second precision
            long windowStart = nowSec - Math.max(0, window.getSeconds());
            if (windowStart > windowEnd) windowStart = windowEnd;

            Map<String, Double> producedColorD = new HashMap<>();
            Map<String, Double> producedMatterD = new HashMap<>();

            Map<String, Double> consumedColorD = new HashMap<>();
            Map<String, Double> consumedMatterD = new HashMap<>();

            Map<String, SoldAcc> soldColorD = new HashMap<>();
            Map<String, SoldAcc> soldMatterD = new HashMap<>();

            long cursor = bucketStartSeconds(windowEnd);
            long minCursor = bucketStartSeconds(windowStart);

            while (cursor >= minCursor) {
                Bucket b = buckets[bucketIndex(cursor)];
                if (b.bucketStartSeconds == cursor) {
                    double frac = overlapFraction(cursor, windowStart, windowEnd);
                    if (frac > 0.0) {
                        addScaledLongMap(b.producedByColor, producedColorD, frac);
                        addScaledLongMap(b.producedByMatter, producedMatterD, frac);

                        addScaledLongMap(b.consumedByColor, consumedColorD, frac);
                        addScaledLongMap(b.consumedByMatter, consumedMatterD, frac);

                        addScaledSoldMap(b.soldByColor, soldColorD, frac);
                        addScaledSoldMap(b.soldByMatter, soldMatterD, frac);
                    }
                }
                cursor -= BUCKET_SIZE_SECONDS;
            }

            Map<String, Long> producedColor = toRoundedLongMap(producedColorD);
            Map<String, Long> producedMatter = toRoundedLongMap(producedMatterD);

            Map<String, Long> consumedColor = toRoundedLongMap(consumedColorD);
            Map<String, Long> consumedMatter = toRoundedLongMap(consumedMatterD);

            Map<String, SoldStats> soldColor = toRoundedSoldMap(soldColorD);
            Map<String, SoldStats> soldMatter = toRoundedSoldMap(soldMatterD);

            long totalProduced = 0L;
            for (long v : producedColor.values()) totalProduced += v;

            long totalConsumed = 0L;
            for (long v : consumedColor.values()) totalConsumed += v;

            long totalSoldQty = 0L;
            double totalMoney = 0.0;
            for (SoldStats s : soldMatter.values()) {
                if (s == null) continue;
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
