package io.github.uwegeercken.bucketeer.adapter.out.s3;

import io.github.uwegeercken.bucketeer.infrastructure.config.server.ServerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.Bucket;

import javax.net.ssl.*;
import java.net.URI;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Component
public class S3ClientRegistry {

    private static final Logger log = LoggerFactory.getLogger(S3ClientRegistry.class);

    private volatile Map<String, S3Client> clients = new LinkedHashMap<>();
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    public void reload(List<ServerConfig> servers) {
        lock.writeLock().lock();
        try {
            clients.values().forEach(c -> {
                try { c.close(); } catch (Exception e) {
                    log.warn("Error closing S3 client: {}", e.getMessage());
                }
            });
            Map<String, S3Client> newClients = new LinkedHashMap<>();
            for (ServerConfig server : servers) {
                newClients.put(server.name(), buildClient(server));
                log.info("Initialized S3 client for '{}' (verifyCert={})",
                        server.name(), server.verifyCertificate());
            }
            clients = newClients;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<String> serverNames() {
        lock.readLock().lock();
        try { return List.copyOf(clients.keySet()); }
        finally { lock.readLock().unlock(); }
    }

    public S3Client clientFor(String serverName) {
        lock.readLock().lock();
        try {
            S3Client client = clients.get(serverName);
            if (client == null) throw new IllegalArgumentException("Unknown S3 server: " + serverName);
            return client;
        } finally { lock.readLock().unlock(); }
    }

    public List<String> listBuckets(String serverName) {
        return clientFor(serverName).listBuckets().buckets()
                .stream().map(Bucket::name).toList();
    }

    private S3Client buildClient(ServerConfig server) {
        S3ClientBuilder builder = S3Client.builder()
                .endpointOverride(URI.create(server.endpoint()))
                .region(Region.of(server.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(server.accessKey(), server.secretKey())))
                .forcePathStyle(true);

        if (!server.verifyCertificate()) {
            builder.httpClientBuilder(UrlConnectionHttpClient.builder()
                    .tlsTrustManagersProvider(
                            () -> new TrustManager[]{ new TrustAllTrustManager() }));
        }

        return builder.build();
    }

    /** Trust manager that accepts all certificates. Only used when verifyCertificate=false. */
    private static class TrustAllTrustManager implements X509TrustManager {
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }
}