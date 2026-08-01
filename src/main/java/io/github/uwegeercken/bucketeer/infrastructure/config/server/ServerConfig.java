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
        String secretKey,
        boolean verifyCertificate,
        int timeoutSeconds,
        int retries
) {
    /** Default constructor with certificate verification enabled. */
    public ServerConfig(String name, String endpoint, String region,
                        String accessKey, String secretKey) {
        this(name, endpoint, region, accessKey, secretKey, true, 0, 3);
    }

    /** Constructor with certificate verification, SDK-default timeout and retries. */
    public ServerConfig(String name, String endpoint, String region,
                        String accessKey, String secretKey, boolean verifyCertificate) {
        this(name, endpoint, region, accessKey, secretKey, verifyCertificate, 0, 3);
    }
}
