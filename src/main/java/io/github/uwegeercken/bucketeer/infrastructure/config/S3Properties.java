package io.github.uwegeercken.bucketeer.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "bucketeer")
public record S3Properties(
        String version,
        String releaseDate
) {}