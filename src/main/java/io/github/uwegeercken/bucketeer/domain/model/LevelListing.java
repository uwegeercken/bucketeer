package io.github.uwegeercken.bucketeer.domain.model;

import java.util.List;

/**
 * Result of a delimiter ("/") listing: the objects directly at the current level
 * and the common prefixes (folders) one level deeper.
 */
public record LevelListing(
        List<S3Object> objects,
        List<String> commonPrefixes,
        String nextContinuationToken,
        boolean truncated
) {
}