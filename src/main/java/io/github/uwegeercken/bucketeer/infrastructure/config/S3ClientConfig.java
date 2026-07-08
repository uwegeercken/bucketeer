package io.github.uwegeercken.bucketeer.infrastructure.config;

import io.github.uwegeercken.bucketeer.adapter.out.s3.S3ClientRegistry;
import io.github.uwegeercken.bucketeer.infrastructure.config.server.ServerConfig;
import io.github.uwegeercken.bucketeer.infrastructure.config.server.ServerConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Initializes the S3ClientRegistry at startup.
 * If ~/.bucketeer/servers.json exists, it takes precedence over application.yml servers.
 */
@Configuration
@EnableConfigurationProperties(S3Properties.class)
public class S3ClientConfig {

    private static final Logger log = LoggerFactory.getLogger(S3ClientConfig.class);

    @Bean
    public S3ClientRegistry s3ClientRegistry(S3Properties props,
                                             ServerConfigRepository repository) {
        S3ClientRegistry registry = new S3ClientRegistry();

        if (repository.configFileExists()) {
            log.info("Loading S3 servers from ~/.bucketeer/servers.json");
            registry.reload(repository.loadAll());
        } else if (props.servers() != null && !props.servers().isEmpty()) {
            log.info("Loading S3 servers from application.yml ({} server(s))", props.servers().size());
            List<ServerConfig> servers = props.servers().stream()
                    .map(e -> new ServerConfig(e.name(), e.endpoint(), e.region(),
                            e.accessKey(), e.secretKey()))
                    .toList();
            registry.reload(servers);
        } else {
            log.warn("No S3 servers configured. Use /config to add servers.");
        }

        return registry;
    }
}
