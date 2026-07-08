package io.github.uwegeercken.bucketeer.infrastructure.config.server;

/**
 * Represents a configured S3 server entry.
 * accessKey and secretKey are stored encrypted in the JSON file.
 */
public record ServerConfig(
        String name,
        String endpoint,
        String region,
        String accessKey,
        String secretKey
) {
    /** Returns a copy with masked credentials for display purposes. */
    public ServerConfig withMaskedCredentials() {
        return new ServerConfig(name, endpoint, region, "***", "***");
    }
}
