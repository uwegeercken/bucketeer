package io.github.uwegeercken.bucketeer.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "bucketeer")
public record S3Properties(
        List<ServerEntry> servers,
        ConfigProperties config
) {
    public record ServerEntry(
            String name,
            String endpoint,
            String region,
            String accessKey,
            String secretKey
    ) {}

    public record ConfigProperties(
            String encryptionKey
    ) {}
}
