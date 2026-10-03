package io.github.uwegeercken.bucketeer.domain.port.out;

import io.github.uwegeercken.bucketeer.domain.model.ObjectListing;
import io.github.uwegeercken.bucketeer.domain.model.PrefixCount;
import io.github.uwegeercken.bucketeer.domain.model.PrefixScan;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

public interface S3StoragePort {

    List<String> serverNames();

    List<String> listBuckets(String serverName);

    ObjectListing listObjects(String serverName, String bucket, String prefix, String continuationToken, long maxKeys);

    /**
     * Scans the next level of common prefixes below the given prefix
     * (ListObjectsV2 with delimiter "/").
     *
     * @param prefix the current path prefix ("" or null scans the top level)
     */
    PrefixScan scanCommonPrefixes(String serverName, String bucket, String prefix,
                                  String continuationToken, long maxKeys);

    /**
     * Counts all common prefixes below the given prefix by paginating through the
     * delimiter listing. Counting stops at an internal limit; {@link PrefixCount#capped()}
     * reports whether that limit was reached.
     */
    PrefixCount countCommonPrefixes(String serverName, String bucket, String prefix);

    InputStream downloadObject(String serverName, String bucket, String key);

    io.github.uwegeercken.bucketeer.domain.model.HeadObjectResult headObject(String serverName, String bucket, String key);

    Map<String, String> getObjectTags(String serverName, String bucket, String key);

    /**
     * Copies an object within the S3 storage.
     *
     * @param overwrite if false, the copy only happens when the destination does not already exist
     * @return true if the object was copied, false if the destination already existed (and overwrite was false)
     */
    boolean copyObject(String serverName, String sourceBucket, String sourceKey,
                       String destinationBucket, String destinationKey, boolean overwrite);

    void deleteObject(String serverName, String bucket, String key);

    void putObject(String serverName, String bucket, String key, byte[] data);
}
