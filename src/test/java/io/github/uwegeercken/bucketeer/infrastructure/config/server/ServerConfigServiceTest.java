package io.github.uwegeercken.bucketeer.infrastructure.config.server;

import io.github.uwegeercken.bucketeer.adapter.out.s3.S3ClientRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ServerConfigServiceTest {

    private final ServerConfigRepository repository = mock(ServerConfigRepository.class);
    private final S3ClientRegistry registry = mock(S3ClientRegistry.class);
    private final ServerConfigService service = new ServerConfigService(repository, registry);

    @Test
    @DisplayName("reorder reorders the client registry when the order changed")
    void reorderUpdatesRegistry() {
        when(repository.reorder(List.of("b", "a"))).thenReturn(true);
        when(repository.serverNames()).thenReturn(List.of("b", "a"));

        service.reorder(List.of("b", "a"));

        verify(registry).reorder(List.of("b", "a"));
        verify(registry, never()).reload(any());
    }

    @Test
    @DisplayName("reorder leaves the client registry alone when nothing changed")
    void reorderNoChange() {
        when(repository.reorder(List.of("a", "b"))).thenReturn(false);

        service.reorder(List.of("a", "b"));

        verify(registry, never()).reorder(any());
    }
}
