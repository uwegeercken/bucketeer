package io.github.uwegeercken.bucketeer.infrastructure.config;

import io.github.uwegeercken.bucketeer.adapter.out.s3.S3ClientRegistry;
import io.github.uwegeercken.bucketeer.infrastructure.config.server.CredentialEncryptor;
import io.github.uwegeercken.bucketeer.infrastructure.config.server.ServerConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Initializes the S3ClientRegistry at startup from ~/.bucketeer/servers.json.
 * If decryption fails (e.g. after key change), the registry starts empty
 * so the app can still start and the user can re-enter credentials via /config.
 */
@Configuration
@EnableConfigurationProperties(S3Properties.class)
public class S3ClientConfig {

    private static final Logger log = LoggerFactory.getLogger(S3ClientConfig.class);

    @Bean
    public S3ClientRegistry s3ClientRegistry(ServerConfigRepository repository) {
        S3ClientRegistry registry = new S3ClientRegistry();
        if (repository.configFileExists()) {
            try {
                log.info("Loading S3 servers from ~/.bucketeer/servers.json");
                registry.reload(repository.loadAll());
            } catch (Exception e) {
                log.error("Failed to load server config: {}", e.getMessage(), e);
                log.warn("Starting with empty server list. Use /config to add servers.");
            }
        } else {
            log.info("No server config found. Use /config to add S3 servers.");
        }
        return registry;
    }
}