package io.github.uwegeercken.bucketeer.domain.model;

import java.time.Instant;

/**
 * A single action history entry (delete / move) recorded in the action log.
 */
public record ActionEntry(
        Instant timestamp,
        Action action,
        Origin origin,
        String batchId,
        String server,
        String bucket,
        String sourceKey,
        String targetKey,
        Status status,
        String error
) {

    public enum Action { MOVE, DELETE }

    public enum Origin { RESULTS, SELECTION }

    public enum Status { MOVED, SKIPPED, DELETED, NOT_AFFECTED, FAILED }
}
