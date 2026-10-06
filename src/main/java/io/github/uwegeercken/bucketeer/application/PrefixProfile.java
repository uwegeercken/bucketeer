package io.github.uwegeercken.bucketeer.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Measured object distribution across the sub prefixes of one listing scope.
 *
 * <p>Produced by a {@link PrefixAnalyzer} before the listing starts and used to pick the
 * cheaper of the two listing tactics: one flat paginated stream (sequential) or one
 * recursive listing per top-level prefix (parallel). The comparison is made on request
 * counts because both tactics transfer the same payload - only the number of S3 round
 * trips differs. Sequential issues roughly {@code ceil(prefixes * objects / page)}
 * requests, parallel issues {@code prefixes * ceil(objects / page)} requests that run on
 * {@code parallelism} workers. That makes splitting worthwhile only when the average
 * prefix holds about {@code page / parallelism} objects or more; below that a single
 * stream wins even though it is single-threaded.
 *
 * @param levelPrefixes top-level common prefixes seen on the first level page (a lower
 *                      bound when the level listing is truncated)
 * @param sampleCount   number of sampled sub prefixes
 * @param medianObjects median objects per sampled sub prefix; samples that needed a
 *                      second page count as {@code page + 1} because only the lower
 *                      bound is known. The median uses the lower middle value, which
 *                      errs towards the sequential listing
 * @param minObjects    fewest objects seen in a sample
 * @param maxObjects    most objects seen in a sample (page-capped)
 * @param measuredAt    when the samples were taken (cache freshness)
 * @param samples       the raw samples, kept for diagnostics and the future report
 * @param cached        whether this profile was served from the analyzer cache instead of
 *                      fresh samples (false for freshly measured profiles)
 */
public record PrefixProfile(
        int levelPrefixes,
        int sampleCount,
        int medianObjects,
        int minObjects,
        int maxObjects,
        Instant measuredAt,
        List<Sample> samples,
        boolean cached
) {
    /** One sampled sub prefix: objects of its first page, page duration, truncation flag. */
    public record Sample(String prefix, int objects, boolean truncated, long durationMs) {
    }

    /** Builds a profile from raw samples; the median takes the lower middle value. */
    public static PrefixProfile of(int levelPrefixes, List<Sample> samples, Instant measuredAt) {
        return of(levelPrefixes, samples, measuredAt, false);
    }

    /** Builds a profile from raw samples; the median takes the lower middle value. */
    public static PrefixProfile of(int levelPrefixes, List<Sample> samples, Instant measuredAt, boolean cached) {
        List<Integer> counts = new ArrayList<>(samples.size());
        for (Sample sample : samples) {
            counts.add(sample.objects());
        }
        counts.sort(Integer::compareTo);
        int median = counts.isEmpty() ? 0 : counts.get((counts.size() - 1) / 2);
        return new PrefixProfile(levelPrefixes, samples.size(), median,
                counts.isEmpty() ? 0 : counts.getFirst(),
                counts.isEmpty() ? 0 : counts.getLast(),
                measuredAt, List.copyOf(samples), cached);
    }

    /** Copy of this profile with the cache-hit flag set. */
    public PrefixProfile asCached() {
        return new PrefixProfile(levelPrefixes, sampleCount, medianObjects, minObjects,
                maxObjects, measuredAt, samples, true);
    }

    /**
     * Whether listing each top-level prefix separately is expected to finish faster than
     * one flat stream for the given worker count. Returns {@code false} for degenerate
     * inputs (no samples, a single top-level prefix, or a worker count below 2), so an
     * incomplete profile can never push a listing into the more expensive path.
     *
     * @param parallelism effective worker count (already clamped by the caller)
     */
    public boolean prefersParallel(int parallelism) {
        if (parallelism < 2 || sampleCount == 0 || levelPrefixes <= 1 || medianObjects <= 0) {
            return false;
        }
        long page = BucketeerService.LISTING_PAGE;
        long pagesPerPrefix = ceilDiv(medianObjects, page);
        long requestsParallel = (long) levelPrefixes * pagesPerPrefix + ceilDiv(levelPrefixes, page);
        long requestsSequential = ceilDiv((long) levelPrefixes * (long) medianObjects, page);
        return requestsParallel < requestsSequential * parallelism;
    }

    private static long ceilDiv(long value, long divisor) {
        return value / divisor + (value % divisor == 0 ? 0 : 1);
    }
}
