package io.github.uwegeercken.bucketeer.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "bucketeer")
public record S3Properties(
        String version,
        String releaseDate,
        Query query,
        Scan scan
) {
    /**
     * Parallel listing settings. parallelism = maximum number of concurrent
     * prefix listings used by a search; 0 disables parallel listing.
     */
    public record Query(int parallelism) {
    }

    public record Scan(int maxPrefixes, int maxDepth) {
    }
}