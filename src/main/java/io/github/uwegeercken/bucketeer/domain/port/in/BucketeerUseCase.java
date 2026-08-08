package io.github.uwegeercken.bucketeer.domain.port.in;

import io.github.uwegeercken.bucketeer.domain.model.ObjectListing;

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
    boolean fetchAllObjects(String serverName, String bucket, String resolvedPrefix,
                            long maxObjects, Consumer<ObjectListing> pageCallback);

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