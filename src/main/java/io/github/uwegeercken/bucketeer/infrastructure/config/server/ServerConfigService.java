package io.github.uwegeercken.bucketeer.infrastructure.config.server;

import io.github.uwegeercken.bucketeer.adapter.out.s3.S3ClientRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.util.List;

/**
 * Manages S3 server configurations: CRUD operations and connection testing.
 * After any change the S3ClientRegistry is reloaded.
 */
@Service
public class ServerConfigService {

    private static final Logger log = LoggerFactory.getLogger(ServerConfigService.class);

    private final ServerConfigRepository repository;
    private final S3ClientRegistry clientRegistry;

    public ServerConfigService(ServerConfigRepository repository, S3ClientRegistry clientRegistry) {
        this.repository     = repository;
        this.clientRegistry = clientRegistry;
    }

    public List<ServerConfig> findAll() {
        return repository.loadAll();
    }

    public boolean exists(String name) {
        return repository.exists(name);
    }

    public void save(ServerConfig server) {
        repository.save(server);
        clientRegistry.reload(repository.loadAll());
        log.info("Saved server '{}' and reloaded client registry", server.name());
    }

    public void delete(String name) {
        repository.delete(name);
        clientRegistry.reload(repository.loadAll());
        log.info("Deleted server '{}' and reloaded client registry", name);
    }

    /**
     * Saves the server and tests the connection by listing buckets.
     *
     * @return null if successful, error message if failed
     */
    public String saveAndTest(ServerConfig server) {
        save(server);
        try {
            List<String> buckets = clientRegistry.listBuckets(server.name());
            log.info("Connection test for '{}' succeeded: {} bucket(s)", server.name(), buckets.size());
            return null;
        } catch (S3Exception e) {
            return "S3 error: " + e.awsErrorDetails().errorMessage()
                    + " (Code: " + e.awsErrorDetails().errorCode() + ")";
        } catch (Exception e) {
            return "Connection failed: " + e.getMessage();
        }
    }
}
