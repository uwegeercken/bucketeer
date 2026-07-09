package io.github.uwegeercken.bucketeer.infrastructure.config;

import io.github.uwegeercken.bucketeer.adapter.out.s3.S3ClientRegistry;
import io.github.uwegeercken.bucketeer.infrastructure.config.server.ServerConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Initializes the S3ClientRegistry at startup from ~/.bucketeer/servers.json.
 * If no config file exists, the registry starts empty – use /config to add servers.
 */
@Configuration
@EnableConfigurationProperties(S3Properties.class)
public class S3ClientConfig {

    private static final Logger log = LoggerFactory.getLogger(S3ClientConfig.class);

    @Bean
    public S3ClientRegistry s3ClientRegistry(ServerConfigRepository repository) {
        S3ClientRegistry registry = new S3ClientRegistry();
        if (repository.configFileExists()) {
            log.info("Loading S3 servers from ~/.bucketeer/servers.json");
            registry.reload(repository.loadAll());
        } else {
            log.warn("No server config found. Use /config to add S3 servers.");
        }
        return registry;
    }
}
