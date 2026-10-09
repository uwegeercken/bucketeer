package io.github.uwegeercken.bucketeer.domain.port.in;

import io.github.uwegeercken.bucketeer.application.ListingReport;
import io.github.uwegeercken.bucketeer.domain.model.ObjectListing;
import io.github.uwegeercken.bucketeer.domain.model.PrefixScan;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public interface BucketeerUseCase {

    List<String> serverNames();

    List<String> listBuckets(String serverName);

    List<String> availableFunctions();

    String resolveTemplate(String template, String key, String bucket);

    List<String> validateTemplate(String template);

    ObjectListing listObjects(String serverName, String bucket, String resolvedPrefix, String continuationToken);

    /**
     * Scans the next level of common prefixes below the given prefix (S3 delimiter listing).
     *
     * @param maxPrefixes  maximum number of prefixes per scan; 0 or negative selects the configured default
     * @param continuationToken token to continue a previously truncated scan, or null
     */
    PrefixScan scanPrefixes(String serverName, String bucket, String prefix,
                            long maxPrefixes, String continuationToken);

    /**
     * Counts the sub-prefixes below each given prefix and returns a display string
     * aligned with the input list (e.g. "12" or "999+" when the count was capped at
     * the internal limit). An entry is "" when the count could not be determined.
     */
    List<String> countPrefixes(String serverName, String bucket, List<String> prefixes);

    /**
     * Fetches ALL objects for the given prefix, paginating through all S3 pages.
     * The pageCallback is called after each S3 page with the objects from that page.
     *
     * @param serverName     the S3 server
     * @param bucket         the bucket
     * @param resolvedPrefix the resolved prefix
     * @param maxObjects     stop after this many objects (0 = no limit)
     * @param pageCallback   called after each page with the objects from that page
     * @return true if the maxObjects limit was reached before all pages were fetched
     */
    default boolean fetchAllObjects(String serverName, String bucket, String resolvedPrefix,
                                    long maxObjects, Consumer<ObjectListing> pageCallback) {
        return fetchAllObjects(serverName, bucket, resolvedPrefix, maxObjects, pageCallback, lr -> { });
    }

    /**
     * Fetches ALL objects for the given prefix, paginating through all S3 pages.
     * The pageCallback is called after each S3 page with the objects from that page.
     * Once the listing strategy has been decided (or skipped), the reportConsumer is
     * handed a {@link io.github.uwegeercken.bucketeer.application.ListingReport} that
     * describes how the query ran.
     *
     * @param serverName     the S3 server
     * @param bucket         the bucket
     * @param resolvedPrefix the resolved prefix
     * @param maxObjects     stop after this many objects (0 = no limit)
     * @param pageCallback   called after each page with the objects from that page
     * @param reportConsumer receives the strategy report for this query (always invoked once)
     * @return true if the maxObjects limit was reached before all pages were fetched
     */
    boolean fetchAllObjects(String serverName, String bucket, String resolvedPrefix,
                            long maxObjects, Consumer<ObjectListing> pageCallback,
                            Consumer<ListingReport> reportConsumer);

    /**
     * Moves an object within the same bucket (copy then delete).
     * The source is only deleted after a successful copy.
     *
     * @return true if the object was moved, false if the target already existed (object skipped)
     */
    boolean moveObject(String serverName, String bucket, String sourceKey, String targetKey);

    void deleteObject(String serverName, String bucket, String key);

    Map<String, String> getObjectTags(String serverName, String bucket, String key);
}