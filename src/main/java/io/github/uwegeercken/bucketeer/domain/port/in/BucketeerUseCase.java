package io.github.uwegeercken.bucketeer.domain.port.in;

import io.github.uwegeercken.bucketeer.domain.model.ObjectListing;

import java.util.List;
import java.util.function.Consumer;

public interface BucketeerUseCase {

    List<String> serverNames();

    List<String> availableFunctions();

    String resolveTemplate(String template, String key);

    List<String> validateTemplate(String template);

    ObjectListing listObjects(String serverName, String bucket, String resolvedPrefix, String continuationToken);

    /**
     * Fetches ALL objects for the given prefix, paginating through all S3 pages.
     * The pageCallback is called after each S3 page with the objects from that page.
     *
     * @param serverName     the S3 server
     * @param bucket         the bucket
     * @param resolvedPrefix the resolved prefix
     * @param pageCallback   called after each page with the objects from that page
     */
    void fetchAllObjects(String serverName, String bucket, String resolvedPrefix,
                         Consumer<ObjectListing> pageCallback);
}