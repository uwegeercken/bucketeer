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
import java.util.Map;

@Component
public class S3Adapter implements S3StoragePort {

    private final Map<String, S3Client> s3Clients;

    public S3Adapter(Map<String, S3Client> s3Clients) {
        this.s3Clients = s3Clients;
    }

    @Override
    public List<String> serverNames() {
        return List.copyOf(s3Clients.keySet());
    }

    @Override
    public ObjectListing listObjects(String serverName, String bucket, String prefix, String continuationToken) {
        ListObjectsV2Request.Builder requestBuilder = ListObjectsV2Request.builder()
                .bucket(bucket)
                .prefix(prefix != null ? prefix : "");

        if (continuationToken != null && !continuationToken.isBlank()) {
            requestBuilder.continuationToken(continuationToken);
        }

        ListObjectsV2Response response = clientFor(serverName).listObjectsV2(requestBuilder.build());

        List<S3Object> objects = response.contents().stream()
                .map(obj -> new S3Object(
                        obj.key(),
                        bucket,
                        obj.size(),
                        obj.lastModified(),
                        obj.eTag()
                ))
                .toList();

        return new ObjectListing(objects, response.nextContinuationToken(), response.isTruncated());
    }

    @Override
    public InputStream downloadObject(String serverName, String bucket, String key) {
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build();
        ResponseInputStream<GetObjectResponse> response = clientFor(serverName).getObject(request);
        return response;
    }

    private S3Client clientFor(String serverName) {
        S3Client client = s3Clients.get(serverName);
        if (client == null) {
            throw new IllegalArgumentException("Unknown S3 server: " + serverName);
        }
        return client;
    }
}
