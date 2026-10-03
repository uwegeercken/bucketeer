package io.github.uwegeercken.bucketeer.domain.model;

import java.util.List;

public record PrefixScan(
        List<String> prefixes,
        String nextContinuationToken,
        boolean truncated
) {
}