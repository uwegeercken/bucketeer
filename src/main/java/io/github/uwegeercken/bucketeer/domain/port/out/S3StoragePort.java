package io.github.uwegeercken.bucketeer.domain.port.out;

import io.github.uwegeercken.bucketeer.domain.model.ObjectListing;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

public interface S3StoragePort {

    List<String> serverNames();

    List<String> listBuckets(String serverName);

    ObjectListing listObjects(String serverName, String bucket, String prefix, String continuationToken, long maxKeys);

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
}
