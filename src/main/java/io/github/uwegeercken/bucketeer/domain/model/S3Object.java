package io.github.uwegeercken.bucketeer.domain.model;

import java.time.Instant;

public record S3Object(
        String key,
        String bucket,
        long sizeBytes,
        Instant lastModified,
        String etag
) {}
