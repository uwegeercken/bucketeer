package io.github.uwegeercken.bucketeer.adapter.in.web;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;

/**
 * A batch entry in the selection (cart). Either a live query batch (empty {@code keys},
 * re-resolved against S3 when an action runs) or an explicit key selection (non-empty {@code keys}).
 */
public record CartBatch(
        String id,
        long seq,
        String serverName,
        String bucket,
        String prefix,
        String exactKey,
        String keyFilter,
        Double minSizeKb,
        Double maxSizeKb,
        String dateFrom,
        String dateTo,
        long maxObjects,
        List<String> keys,
        long count,
        long totalSizeBytes,
        Instant createdAt
) implements Serializable {

    public boolean isLiveQuery() {
        return keys == null || keys.isEmpty();
    }
}
