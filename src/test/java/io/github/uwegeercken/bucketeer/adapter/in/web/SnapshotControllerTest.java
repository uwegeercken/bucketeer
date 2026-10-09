package io.github.uwegeercken.bucketeer.adapter.in.web;

import io.github.uwegeercken.bucketeer.domain.port.in.BucketeerUseCase;
import io.github.uwegeercken.bucketeer.infrastructure.config.SnapshotRepository;
import io.github.uwegeercken.bucketeer.infrastructure.db.DuckDbRepository;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SnapshotControllerTest {

    private final SnapshotRepository snapshotRepo = mock(SnapshotRepository.class);
    private final DuckDbRepository duckDb = mock(DuckDbRepository.class);
    private final BucketeerUseCase bucketeerUseCase = mock(BucketeerUseCase.class);
    private final BucketeerController bucketeerController = mock(BucketeerController.class);

    private SnapshotController newController(SessionContext ctx) {
        return new SnapshotController(duckDb, snapshotRepo, ctx, bucketeerUseCase, bucketeerController);
    }

    private SnapshotMeta snapshot(String server) {
        return new SnapshotMeta("s1", "My snapshot", Instant.parse("2026-10-09T10:00:00Z"),
                server, "topf1", "events/", "", null, null, null, 42);
    }

    private void stubSnapshotWithData(String server, Path tempDir) throws IOException {
        when(snapshotRepo.findById("s1")).thenReturn(snapshot(server));
        when(snapshotRepo.getSnapshotsDir()).thenReturn(tempDir);
        Files.write(tempDir.resolve("snapshot_s1_data.parquet"), new byte[]{1});
        when(duckDb.loadParquet(anyString())).thenReturn(42L);
    }

    @Test
    @DisplayName("loadSnapshot switches the selected server to the snapshot's server when it differs")
    void loadSnapshotSwitchesServer(@TempDir Path tempDir) throws Exception {
        stubSnapshotWithData("Minio Local 2", tempDir);
        SessionContext ctx = new SessionContext();
        ctx.setSelectedServer("Minio Local");
        SnapshotController controller = newController(ctx);

        ResponseEntity<Map<String, Object>> resp = controller.loadSnapshot("s1", mock(HttpSession.class));

        assertThat(resp.getBody()).containsEntry("ok", true).containsEntry("rowCount", 42L);
        assertThat(ctx.getSelectedServer()).isEqualTo("Minio Local 2");
    }

    @Test
    @DisplayName("loadSnapshot keeps the selected server when the snapshot matches the current server")
    void loadSnapshotKeepsServerWhenSame(@TempDir Path tempDir) throws Exception {
        stubSnapshotWithData("Minio Local", tempDir);
        SessionContext ctx = new SessionContext();
        ctx.setSelectedServer("Minio Local");
        SnapshotController controller = newController(ctx);

        controller.loadSnapshot("s1", mock(HttpSession.class));

        assertThat(ctx.getSelectedServer()).isEqualTo("Minio Local");
    }

    @Test
    @DisplayName("loadSnapshot keeps the selected server when the snapshot has no server")
    void loadSnapshotKeepsServerWhenSnapshotHasNone(@TempDir Path tempDir) throws Exception {
        stubSnapshotWithData(null, tempDir);
        SessionContext ctx = new SessionContext();
        ctx.setSelectedServer("Minio Local");
        SnapshotController controller = newController(ctx);

        controller.loadSnapshot("s1", mock(HttpSession.class));

        assertThat(ctx.getSelectedServer()).isEqualTo("Minio Local");
    }
}