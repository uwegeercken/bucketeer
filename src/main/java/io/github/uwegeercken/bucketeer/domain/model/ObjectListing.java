package io.github.uwegeercken.bucketeer.domain.model;

import java.util.List;

public record ObjectListing(
        List<S3Object> objects,
        String nextContinuationToken,
        boolean truncated
) {
    public ObjectListing withObjects(List<S3Object> filteredObjects) {
        return new ObjectListing(filteredObjects, nextContinuationToken, truncated);
    }
}
