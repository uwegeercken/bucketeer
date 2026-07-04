package io.github.uwegeercken.bucketeer.domain.model;

import java.util.List;

public record ObjectListing(
        List<S3Object> objects,
        String nextContinuationToken,
        boolean truncated
) {}
