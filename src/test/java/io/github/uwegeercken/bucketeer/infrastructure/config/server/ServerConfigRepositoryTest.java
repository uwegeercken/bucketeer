package io.github.uwegeercken.bucketeer.infrastructure.config.server;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ServerConfigRepositoryTest {

    @TempDir
    Path tempDir;

    private Path configFile;
    private CredentialEncryptor encryptor;
    private ServerConfigRepository repository;

    @BeforeEach
    void setUp() {
        encryptor = mock(CredentialEncryptor.class);
        when(encryptor.encrypt(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(encryptor.decrypt(anyString())).thenAnswer(inv -> inv.getArgument(0));
        configFile = tempDir.resolve("servers.json");
        repository = new ServerConfigRepository(encryptor, configFile);
        repository.saveAll(List.of(server("a"), server("b"), server("c")));
    }

    private static ServerConfig server(String name) {
        return new ServerConfig(name, "http://localhost:9000", "us-east-1", "ak", "sk", true);
    }

    private List<String> names() {
        return repository.loadAll().stream().map(ServerConfig::name).toList();
    }

    @Test
    @DisplayName("saving an existing server keeps its position")
    void saveExistingKeepsPosition() {
        repository.save(new ServerConfig("a", "http://other:9000", "eu-west-1", "ak", "sk", false));

        assertThat(names()).containsExactly("a", "b", "c");
        assertThat(repository.loadAll().getFirst().endpoint()).isEqualTo("http://other:9000");
    }

    @Test
    @DisplayName("saving a new server appends it at the end")
    void saveNewAppends() {
        repository.save(server("d"));

        assertThat(names()).containsExactly("a", "b", "c", "d");
    }

    @Test
    @DisplayName("moving a server up swaps it with its predecessor and persists")
    void moveUp() {
        assertThat(repository.move("c", -1)).isTrue();

        assertThat(names()).containsExactly("a", "c", "b");
    }

    @Test
    @DisplayName("moving a server down swaps it with its successor and persists")
    void moveDown() {
        assertThat(repository.move("a", 1)).isTrue();

        assertThat(names()).containsExactly("b", "a", "c");
    }

    @Test
    @DisplayName("moving beyond the list edges is a no-op")
    void moveAtEdgesIsNoOp() throws Exception {
        String before = Files.readString(configFile);

        assertThat(repository.move("a", -1)).isFalse();
        assertThat(repository.move("c", 1)).isFalse();

        assertThat(Files.readString(configFile)).isEqualTo(before);
    }

    @Test
    @DisplayName("moving an unknown server returns false")
    void moveUnknown() {
        assertThat(repository.move("nope", 1)).isFalse();
        assertThat(names()).containsExactly("a", "b", "c");
    }

    @Test
    @DisplayName("moving a server neither encrypts nor decrypts credentials")
    void moveDoesNoCrypto() {
        clearInvocations(encryptor);

        repository.move("b", -1);
        repository.serverNames();
        List<String> names = names();   // served from the cache kept warm by move

        assertThat(names).containsExactly("b", "a", "c");
        verify(encryptor, never()).encrypt(anyString());
        verify(encryptor, never()).decrypt(anyString());
    }

    @Test
    @DisplayName("loadAll decrypts once and serves repeat calls from the cache")
    void loadAllIsCached() {
        repository.saveAll(List.of(server("x")));
        clearInvocations(encryptor);

        ServerConfigRepository fresh = new ServerConfigRepository(encryptor, configFile);
        fresh.loadAll();
        fresh.loadAll();

        verify(encryptor, times(2)).decrypt(anyString());   // accessKey + secretKey, once
    }

    @Test
    @DisplayName("an external change to servers.json invalidates the cache")
    void externalChangeInvalidatesCache() throws Exception {
        ServerConfigRepository other = new ServerConfigRepository(encryptor, configFile);
        other.saveAll(List.of(server("z"), server("y")));
        Files.setLastModifiedTime(configFile, FileTime.fromMillis(System.currentTimeMillis() + 5_000));

        assertThat(names()).containsExactly("z", "y");
    }

    @Test
    @DisplayName("serverNames returns names in file order")
    void serverNamesInOrder() {
        assertThat(repository.serverNames()).containsExactly("a", "b", "c");
    }
}
