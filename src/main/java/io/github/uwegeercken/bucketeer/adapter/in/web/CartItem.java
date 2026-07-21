package io.github.uwegeercken.bucketeer.adapter.in.web;

import java.io.Serializable;
import java.time.Instant;

public record CartItem(
        String serverName,
        String bucket,
        String key,
        long sizeBytes,
        Instant lastModified
) implements Serializable {

    public String filename() {
        int idx = key.lastIndexOf('/');
        return idx >= 0 ? key.substring(idx + 1) : key;
    }
}
