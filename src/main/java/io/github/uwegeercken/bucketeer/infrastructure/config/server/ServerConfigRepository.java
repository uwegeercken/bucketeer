package io.github.uwegeercken.bucketeer.infrastructure.config.server;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

@Repository
public class ServerConfigRepository {

    private static final Logger log = LoggerFactory.getLogger(ServerConfigRepository.class);
    private static final String CONFIG_FILE = ".bucketeer/servers.json";

    private final Path configPath;
    private final ObjectMapper mapper;
    private final CredentialEncryptor encryptor;

    /** Decrypted servers as of {@link #cacheStamp}; null when not loaded / invalidated. */
    private List<ServerConfig> cache;
    private FileStamp cacheStamp;

    @Autowired
    public ServerConfigRepository(CredentialEncryptor encryptor) {
        this(encryptor, Path.of(System.getProperty("user.home"), CONFIG_FILE));
    }

    /** Package-private: lets tests point the repository at a temp file. */
    ServerConfigRepository(CredentialEncryptor encryptor, Path configPath) {
        this.encryptor  = encryptor;
        this.configPath = configPath;
        this.mapper     = JsonMapper.builder()
                .enable(SerializationFeature.INDENT_OUTPUT).build();
    }

    public boolean configFileExists() {
        return Files.exists(configPath);
    }

    /**
     * Returns all servers with decrypted credentials, in file order.
     *
     * <p>Decryption is expensive (PBKDF2 key derivation per credential), so the decrypted list is
     * cached and only re-read when the file's modification time or size changes - this also picks
     * up edits made to {@code servers.json} outside the application.
     */
    public synchronized List<ServerConfig> loadAll() {
        if (!Files.exists(configPath)) {
            invalidateCache();
            return List.of();
        }
        FileStamp stamp = currentStamp();
        if (cache != null && stamp != null && stamp.equals(cacheStamp)) {
            return cache;
        }
        try {
            List<ServerConfig> servers = readEntries().stream()
                    .map(e -> new ServerConfig(
                            e.name(), e.endpoint(), e.region(),
                            encryptor.decrypt(e.accessKey()),
                            encryptor.decrypt(e.secretKey()),
                            e.verifyCertificate(),
                            e.timeoutSeconds() != null ? e.timeoutSeconds() : 0,
                            e.retries() != null ? e.retries() : 3))
                    .toList();
            cache      = servers;
            cacheStamp = stamp;
            return servers;
        } catch (CredentialEncryptor.EncryptionException e) {
            log.error("Failed to decrypt server credentials - encryption key may have changed. " +
                    "Please re-enter credentials via /config: {}", e.getMessage());
            invalidateCache();
            return List.of();
        } catch (tools.jackson.core.JacksonException e) {
            log.error("Failed to load server config from {}: {}", configPath, e.getMessage());
            invalidateCache();
            return List.of();
        }
    }

    public synchronized void saveAll(List<ServerConfig> servers) {
        List<JsonServerEntry> entries = servers.stream()
                .map(s -> new JsonServerEntry(
                        s.name(), s.endpoint(), s.region(),
                        encryptor.encrypt(s.accessKey()),
                        encryptor.encrypt(s.secretKey()),
                        s.verifyCertificate(),
                        s.timeoutSeconds(),
                        s.retries()))
                .toList();
        writeEntries(entries);
        cache      = List.copyOf(servers);
        cacheStamp = currentStamp();
        log.info("Saved {} server(s) to {}", servers.size(), configPath);
    }

    public synchronized void save(ServerConfig server) {
        List<ServerConfig> current = new ArrayList<>(loadAll());
        int idx = indexOf(current, ServerConfig::name, server.name());
        if (idx >= 0) {
            current.set(idx, server);   // keep user-defined position
        } else {
            current.add(server);
        }
        saveAll(current);
    }

    /**
     * Moves the named server by {@code delta} positions (-1 = up, +1 = down).
     * Swaps the stored (still encrypted) entries directly - no decrypt/re-encrypt round trip.
     *
     * @return true if the order changed, false if the server is unknown or already at the edge
     */
    public synchronized boolean move(String name, int delta) {
        if (!Files.exists(configPath)) return false;
        FileStamp before = currentStamp();
        List<JsonServerEntry> entries = new ArrayList<>(readEntries());
        int idx = indexOf(entries, JsonServerEntry::name, name);
        int target = idx + delta;
        if (idx < 0 || target < 0 || target >= entries.size()) return false;
        Collections.swap(entries, idx, target);
        writeEntries(entries);

        // Keep the decrypted cache warm if it reflected the file we just rewrote.
        if (cache != null && before != null && before.equals(cacheStamp)
                && cache.size() == entries.size()) {
            List<ServerConfig> reordered = new ArrayList<>(cache);
            Collections.swap(reordered, idx, target);
            cache      = List.copyOf(reordered);
            cacheStamp = currentStamp();
        } else {
            invalidateCache();
        }
        return true;
    }

    /** Server names in file order, read without decrypting any credentials. */
    public synchronized List<String> serverNames() {
        if (!Files.exists(configPath)) return List.of();
        try {
            return readEntries().stream().map(JsonServerEntry::name).toList();
        } catch (tools.jackson.core.JacksonException e) {
            log.error("Failed to load server config from {}: {}", configPath, e.getMessage());
            return List.of();
        }
    }

    private List<JsonServerEntry> readEntries() {
        return mapper.readValue(configPath.toFile(), new TypeReference<List<JsonServerEntry>>() {});
    }

    private void writeEntries(List<JsonServerEntry> entries) {
        try {
            Files.createDirectories(configPath.getParent());
            mapper.writeValue(configPath.toFile(), entries);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save server config to " + configPath, e);
        }
    }

    private void invalidateCache() {
        cache      = null;
        cacheStamp = null;
    }

    private FileStamp currentStamp() {
        try {
            return new FileStamp(Files.getLastModifiedTime(configPath), Files.size(configPath));
        } catch (IOException e) {
            return null;
        }
    }

    private static <T> int indexOf(List<T> items, Function<T, String> nameOf, String name) {
        for (int i = 0; i < items.size(); i++) {
            if (nameOf.apply(items.get(i)).equals(name)) return i;
        }
        return -1;
    }

    private record FileStamp(FileTime modified, long size) {}

    public synchronized void delete(String name) {
        List<ServerConfig> current = new ArrayList<>(loadAll());
        current.removeIf(s -> s.name().equals(name));
        saveAll(current);
    }

    public boolean exists(String name) {
        return loadAll().stream().anyMatch(s -> s.name().equals(name));
    }

    private record JsonServerEntry(
            String name,
            String endpoint,
            String region,
            String accessKey,
            String secretKey,
            boolean verifyCertificate,
            Integer timeoutSeconds,
            Integer retries
    ) {}
}