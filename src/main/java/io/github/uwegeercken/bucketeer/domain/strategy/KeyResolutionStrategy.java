package io.github.uwegeercken.bucketeer.domain.strategy;

/**
 * Strategy for resolving an S3 object prefix from a given key.
 * Implementations define how a key is transformed into a path within the bucket.
 */
public interface KeyResolutionStrategy {

    /** Display name shown in the UI dropdown. */
    String name();

    /**
     * Resolves the full S3 prefix used to list objects for the given key.
     *
     * @param prefix the base prefix configured for the bucket (may be empty)
     * @param key    the object key as entered by the user
     * @return the full prefix to use for the S3 listObjects call
     */
    String resolvePrefix(String prefix, String key);
}
