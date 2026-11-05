package io.github.uwegeercken.bucketeer.adapter.out.s3;

import io.github.uwegeercken.bucketeer.infrastructure.config.server.ServerConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class S3ClientRegistryTest {

    private static ServerConfig server(String name) {
        return new ServerConfig(name, "http://localhost:9000", "us-east-1", "ak", "sk", true);
    }

    @Test
    @DisplayName("reorder changes the name order but keeps the existing client instances")
    void reorderKeepsClients() {
        S3ClientRegistry registry = new S3ClientRegistry();
        registry.reload(List.of(server("a"), server("b"), server("c")));
        S3Client clientA = registry.clientFor("a");

        registry.reorder(List.of("c", "a", "b"));

        assertThat(registry.serverNames()).containsExactly("c", "a", "b");
        assertThat(registry.clientFor("a")).isSameAs(clientA);
    }

    @Test
    @DisplayName("reorder ignores unknown names and keeps unlisted clients at the end")
    void reorderPartialList() {
        S3ClientRegistry registry = new S3ClientRegistry();
        registry.reload(List.of(server("a"), server("b"), server("c")));

        registry.reorder(List.of("c", "nope"));

        assertThat(registry.serverNames()).containsExactly("c", "a", "b");
    }
}
