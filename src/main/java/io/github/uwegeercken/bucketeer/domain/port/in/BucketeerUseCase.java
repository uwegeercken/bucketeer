package io.github.uwegeercken.bucketeer.domain.port.in;

import io.github.uwegeercken.bucketeer.domain.model.ObjectListing;

import java.util.List;

public interface BucketeerUseCase {

    List<String> serverNames();

    List<String> availableFunctions();

    String resolveTemplate(String template, String key);

    List<String> validateTemplate(String template);

    ObjectListing listObjects(String serverName, String bucket, String resolvedPrefix, String continuationToken);
}
