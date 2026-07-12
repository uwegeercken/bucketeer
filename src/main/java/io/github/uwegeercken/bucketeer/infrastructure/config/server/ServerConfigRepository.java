package io.github.uwegeercken.bucketeer.infrastructure.config.server;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Repository
public class ServerConfigRepository {

    private static final Logger log = LoggerFactory.getLogger(ServerConfigRepository.class);
    private static final String CONFIG_FILE = ".bucketeer/servers.json";

    private final Path configPath;
    private final ObjectMapper mapper;
    private final CredentialEncryptor encryptor;

    public ServerConfigRepository(CredentialEncryptor encryptor) {
        this.encryptor  = encryptor;
        this.configPath = Path.of(System.getProperty("user.home"), CONFIG_FILE);
        this.mapper     = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    public boolean configFileExists() {
        return Files.exists(configPath);
    }

    public List<ServerConfig> loadAll() {
        if (!Files.exists(configPath)) return List.of();
        try {
            List<JsonServerEntry> entries = mapper.readValue(configPath.toFile(),
                    new TypeReference<List<JsonServerEntry>>() {});
            return entries.stream()
                    .map(e -> new ServerConfig(
                            e.name(), e.endpoint(), e.region(),
                            encryptor.decrypt(e.accessKey()),
                            encryptor.decrypt(e.secretKey()),
                            e.verifyCertificate()))
                    .toList();
        } catch (CredentialEncryptor.EncryptionException e) {
            log.error("Failed to decrypt server credentials - encryption key may have changed. " +
                    "Please re-enter credentials via /config: {}", e.getMessage());
            return List.of();
        } catch (IOException e) {
            log.error("Failed to load server config from {}: {}", configPath, e.getMessage(), e);
            return List.of();
        }
    }

    public void saveAll(List<ServerConfig> servers) {
        try {
            Files.createDirectories(configPath.getParent());
            List<JsonServerEntry> entries = servers.stream()
                    .map(s -> new JsonServerEntry(
                            s.name(), s.endpoint(), s.region(),
                            encryptor.encrypt(s.accessKey()),
                            encryptor.encrypt(s.secretKey()),
                            s.verifyCertificate()))
                    .toList();
            mapper.writeValue(configPath.toFile(), entries);
            log.info("Saved {} server(s) to {}", servers.size(), configPath);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save server config to " + configPath, e);
        }
    }

    public void save(ServerConfig server) {
        List<ServerConfig> current = new ArrayList<>(loadAll());
        current.removeIf(s -> s.name().equals(server.name()));
        current.add(server);
        saveAll(current);
    }

    public void delete(String name) {
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
            boolean verifyCertificate
    ) {}
}