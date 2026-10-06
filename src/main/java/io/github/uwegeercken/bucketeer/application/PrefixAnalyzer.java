package io.github.uwegeercken.bucketeer.application;

import java.util.List;

/**
 * Estimates how objects are distributed across the sub prefixes of a listing scope so
 * {@link BucketeerService} can choose between a sequential and a parallel listing.
 */
public interface PrefixAnalyzer {

    /**
     * Samples sub prefixes drawn from the given top-level prefixes and measures how many
     * objects each of them holds. Implementations may serve the result from a
     * short-lived cache.
     *
     * @param serverName    S3 server the scope belongs to
     * @param bucket        bucket name
     * @param prefix        resolved listing prefix (part of the cache key)
     * @param levelPrefixes sub prefixes from the first level page; the samples are drawn
     *                      from this list, never from a full prefix walk
     * @param samples       requested sample count (clamped to 1..64 by AppSettings)
     * @param parallelism   effective worker count; sizes the sampling pool and the
     *                      subsequent decision
     * @return the measured profile, never {@code null}
     * @throws RuntimeException when the sampling listings fail; callers fall back to a
     *                          sequential listing
     */
    PrefixProfile profileFor(String serverName, String bucket, String prefix,
                             List<String> levelPrefixes, int samples, int parallelism);
}
