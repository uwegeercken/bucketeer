package io.github.uwegeercken.bucketeer.adapter.out.s3;

import io.github.uwegeercken.bucketeer.domain.model.ObjectListing;
import io.github.uwegeercken.bucketeer.domain.model.S3Object;
import io.github.uwegeercken.bucketeer.domain.port.out.S3StoragePort;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.InputStream;
import java.util.List;

@Component
public class S3Adapter implements S3StoragePort {

    private final S3ClientRegistry registry;

    public S3Adapter(S3ClientRegistry registry) {
        this.registry = registry;
    }

    @Override
    public List<String> serverNames() {
        return registry.serverNames();
    }

    @Override
    public ObjectListing listObjects(String serverName, String bucket, String prefix, String continuationToken) {
        S3Client client = registry.clientFor(serverName);

        ListObjectsV2Request.Builder builder = ListObjectsV2Request.builder()
                .bucket(bucket)
                .prefix(prefix != null ? prefix : "");
        if (continuationToken != null && !continuationToken.isBlank()) {
            builder.continuationToken(continuationToken);
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
}
