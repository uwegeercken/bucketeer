package io.github.uwegeercken.bucketeer.adapter.out.s3;

import io.github.uwegeercken.bucketeer.domain.model.HeadObjectResult;
import io.github.uwegeercken.bucketeer.domain.model.ObjectListing;
import io.github.uwegeercken.bucketeer.domain.model.S3Object;
import io.github.uwegeercken.bucketeer.domain.port.out.S3StoragePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.InputStream;
import java.util.List;

@Component
public class S3Adapter implements S3StoragePort {

    private static final Logger log = LoggerFactory.getLogger(S3Adapter.class);

    private final S3ClientRegistry registry;

    public S3Adapter(S3ClientRegistry registry) {
        this.registry = registry;
    }

    @Override
    public List<String> serverNames() {
        return registry.serverNames();
    }

    @Override
    public List<String> listBuckets(String serverName) {
        return registry.listBuckets(serverName);
    }

    @Override
    public ObjectListing listObjects(String serverName, String bucket, String prefix, String continuationToken, long maxKeys) {
        S3Client client = registry.clientFor(serverName);

        ListObjectsV2Request.Builder builder = ListObjectsV2Request.builder()
                .bucket(bucket)
                .prefix(prefix != null ? prefix : "");
        if (continuationToken != null && !continuationToken.isBlank()) {
            builder.continuationToken(continuationToken);
        }
        if (maxKeys > 0) {
            builder.maxKeys((int) Math.min(maxKeys, 1000));
        }

        ListObjectsV2Response response = client.listObjectsV2(builder.build());
        List<S3Object> objects = response.contents().stream()
                .map(obj -> new S3Object(obj.key(), bucket, obj.size(), obj.lastModified(), obj.eTag()))
                .toList();

        return new ObjectListing(objects, response.nextContinuationToken(), response.isTruncated());
    }

    @Override
    public InputStream downloadObject(String serverName, String bucket, String key) {
        S3Client client = registry.clientFor(serverName);
        GetObjectRequest request = GetObjectRequest.builder().bucket(bucket).key(key).build();
        ResponseInputStream<GetObjectResponse> response = client.getObject(request);
        return response;
    }

    @Override
    public HeadObjectResult headObject(String serverName, String bucket, String key) {
        try {
            S3Client client = registry.clientFor(serverName);
            HeadObjectRequest request = HeadObjectRequest.builder().bucket(bucket).key(key).build();
            HeadObjectResponse response = client.headObject(request);
            return new HeadObjectResult(
                    true,
                    response.contentLength(),
                    response.lastModified(),
                    response.eTag()
            );
        } catch (NoSuchKeyException e) {
            return HeadObjectResult.notFound();
        } catch (Exception e) {
            log.error("HEAD request failed for {}/{}: {}", bucket, key, e.getMessage());
            return HeadObjectResult.notFound();
        }
    }

    @Override
    public boolean copyObject(String serverName, String sourceBucket, String sourceKey,
                              String destinationBucket, String destinationKey, boolean overwrite) {
        if (!overwrite && headObject(serverName, destinationBucket, destinationKey).exists()) {
            return false;
        }

        S3Client client = registry.clientFor(serverName);

        CopyObjectRequest request = CopyObjectRequest.builder()
                .sourceBucket(sourceBucket)
                .sourceKey(sourceKey)
                .destinationBucket(destinationBucket)
                .destinationKey(destinationKey)
                .build();

        try {
            client.copyObject(request);
            return true;
        } catch (S3Exception e) {
            throw e;
        }
    }

    @Override
    public void deleteObject(String serverName, String bucket, String key) {
        S3Client client = registry.clientFor(serverName);
        client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }
}
