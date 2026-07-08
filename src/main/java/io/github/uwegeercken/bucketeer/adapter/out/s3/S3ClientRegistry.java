package io.github.uwegeercken.bucketeer.adapter.out.s3;

import io.github.uwegeercken.bucketeer.infrastructure.config.server.ServerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Bucket;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Manages a map of S3Client instances keyed by server name.
 * Thread-safe: uses a ReadWriteLock to allow concurrent reads and safe reloads.
 */
@Component
public class S3ClientRegistry {

    private static final Logger log = LoggerFactory.getLogger(S3ClientRegistry.class);

    private volatile Map<String, S3Client> clients = new LinkedHashMap<>();
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    /** Initializes the registry from a list of server configs. */
    public void reload(List<ServerConfig> servers) {
        lock.writeLock().lock();
        try {
            // close existing clients
            clients.values().forEach(c -> {
                try { c.close(); } catch (Exception e) { log.warn("Error closing S3 client: {}", e.getMessage()); }
            });

            Map<String, S3Client> newClients = new LinkedHashMap<>();
            for (ServerConfig server : servers) {
                newClients.put(server.name(), buildClient(server));
                log.info("Initialized S3 client for '{}'", server.name());
            }
            clients = newClients;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<String> serverNames() {
        lock.readLock().lock();
        try {
            return List.copyOf(clients.keySet());
        } finally {
            lock.readLock().unlock();
        }
    }

    public S3Client clientFor(String serverName) {
        lock.readLock().lock();
        try {
            S3Client client = clients.get(serverName);
            if (client == null) throw new IllegalArgumentException("Unknown S3 server: " + serverName);
            return client;
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<String> listBuckets(String serverName) {
        return clientFor(serverName).listBuckets().buckets()
                .stream().map(Bucket::name).toList();
    }

    private S3Client buildClient(ServerConfig server) {
        return S3Client.builder()
                .endpointOverride(URI.create(server.endpoint()))
                .region(Region.of(server.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(server.accessKey(), server.secretKey())))
                .forcePathStyle(true)
                .build();
    }
}
