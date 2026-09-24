package io.github.bearisbug.synaxis.loadtest;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.TreeMap;

/**
 * Values rounded to a fixed resolution and counted, so that the histograms of several bot processes
 * merge exactly: a percentile of the merge is the percentile of all the values, which percentiles of
 * the parts would not give. Thread safe.
 */
final class Histogram {

    private final double resolution;
    private final TreeMap<Long, Long> counts = new TreeMap<>();

    /**
     * @param resolution the step values are rounded to, 1 for counts, 0.1 for milliseconds
     */
    Histogram(double resolution) {
        this.resolution = resolution;
    }

    synchronized void record(double value) {
        counts.merge(Math.round(value / resolution), 1L, Long::sum);
    }

    synchronized void merge(Histogram other) {
        if (other.resolution != resolution)
            throw new IllegalArgumentException("resolution " + other.resolution + " is not " + resolution);
        final Map<Long, Long> theirs;
        synchronized (other) {
            theirs = new TreeMap<>(other.counts);
        }
        theirs.forEach((bucket, count) -> counts.merge(bucket, count, Long::sum));
    }

    synchronized long count() {
        return counts.values().stream().mapToLong(Long::longValue).sum();
    }

    /**
     * The nearest rank percentile, the same rule as {@code soak.py}: the smallest value at or above
     * {@code fraction} of all values.
     *
     * @param fraction 0.5 for the median
     * @return the value, 0 for an empty histogram
     */
    synchronized double percentile(double fraction) {
        final long total = count();
        if (total == 0) return 0;
        final long rank = Math.max(1, (long) Math.ceil(fraction * total));
        long seen = 0;
        for (Map.Entry<Long, Long> entry : counts.entrySet()) {
            seen += entry.getValue();
            if (seen >= rank) return entry.getKey() * resolution;
        }
        return counts.lastKey() * resolution;
    }

    synchronized double max() {
        return counts.isEmpty() ? 0 : counts.lastKey() * resolution;
    }

    synchronized double min() {
        return counts.isEmpty() ? 0 : counts.firstKey() * resolution;
    }

    synchronized double mean() {
        final long total = count();
        if (total == 0) return 0;
        double sum = 0;
        for (Map.Entry<Long, Long> entry : counts.entrySet()) sum += entry.getKey() * resolution * entry.getValue();
        return sum / total;
    }

    /** How many values are above {@code limit}. */
    synchronized long above(double limit) {
        long above = 0;
        for (Map.Entry<Long, Long> entry : counts.entrySet())
            if (entry.getKey() * resolution > limit) above += entry.getValue();
        return above;
    }

    /** The raw form a bot process writes, which {@link #fromJson} reads back for the merge. */
    synchronized JsonObject toJson() {
        final JsonObject json = new JsonObject();
        json.addProperty("resolution", resolution);
        final JsonObject buckets = new JsonObject();
        counts.forEach((bucket, count) -> buckets.addProperty(Long.toString(bucket), count));
        json.add("buckets", buckets);
        return json;
    }

    static Histogram fromJson(JsonObject json) {
        final Histogram histogram = new Histogram(json.get("resolution").getAsDouble());
        for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject("buckets").entrySet())
            histogram.counts.put(Long.parseLong(entry.getKey()), entry.getValue().getAsLong());
        return histogram;
    }

    /**
     * The summary a report quotes, in the {@code soak.py} shape.
     *
     * @param unit the suffix of the keys, {@code _ms} for times and empty for counts
     * @return count, mean, p50, p99, max and, for times, how many were above 50 ms
     */
    JsonObject distribution(String unit) {
        final JsonObject json = new JsonObject();
        json.addProperty("count", count());
        if (count() == 0) return json;
        json.addProperty("mean" + unit, round(mean()));
        json.addProperty("p50" + unit, round(percentile(0.50)));
        json.addProperty("p99" + unit, round(percentile(0.99)));
        json.addProperty("max" + unit, round(max()));
        if (unit.equals("_ms")) json.addProperty("over_50ms", above(50.0));
        return json;
    }

    static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
