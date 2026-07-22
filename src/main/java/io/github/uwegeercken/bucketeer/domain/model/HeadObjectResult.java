package io.github.uwegeercken.bucketeer.domain.model;

import java.time.Instant;

public record HeadObjectResult(
        boolean exists,
        long sizeBytes,
        Instant lastModified,
        String eTag
) {
    public static HeadObjectResult notFound() {
        return new HeadObjectResult(false, 0, null, null);
    }
}
