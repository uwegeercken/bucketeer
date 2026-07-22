package io.github.uwegeercken.bucketeer.domain.port.out;

import io.github.uwegeercken.bucketeer.domain.model.ObjectListing;

import java.io.InputStream;
import java.util.List;

public interface S3StoragePort {

    List<String> serverNames();

    List<String> listBuckets(String serverName);

    ObjectListing listObjects(String serverName, String bucket, String prefix, String continuationToken);

    InputStream downloadObject(String serverName, String bucket, String key);

    io.github.uwegeercken.bucketeer.domain.model.HeadObjectResult headObject(String serverName, String bucket, String key);
}
