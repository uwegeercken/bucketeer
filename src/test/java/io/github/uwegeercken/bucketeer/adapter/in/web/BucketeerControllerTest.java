package io.github.uwegeercken.bucketeer.adapter.in.web;

import io.github.uwegeercken.bucketeer.domain.model.ActionEntry;
import io.github.uwegeercken.bucketeer.domain.port.in.BucketeerUseCase;
import io.github.uwegeercken.bucketeer.domain.port.out.S3StoragePort;
import io.github.uwegeercken.bucketeer.infrastructure.db.DuckDbRepository;
import io.github.uwegeercken.bucketeer.infrastructure.history.ActionHistory;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BucketeerControllerTest {

    private BucketeerUseCase useCase;
    private RecordingDuckDb duckDb;
    private BucketeerController controller;

    @BeforeEach
    void setUp() {
        useCase = mock(BucketeerUseCase.class);
        duckDb = new RecordingDuckDb();
        SessionContext sessionContext = new SessionContext() {
            @Override
            public String getSelectedServer() {
                return "server";
            }
        };
        S3StoragePort storage = mock(S3StoragePort.class);
        ActionHistory actionHistory = new RecordingActionHistory();
        controller = new BucketeerController(useCase, storage, sessionContext, duckDb,
                new ThreadPoolTaskExecutor(), actionHistory);
    }

    @Test
    @DisplayName("a skipped move leaves the source row in the results cache")
    void moveSkippedKeepsSourceRow() {
        when(useCase.moveObject("server", "bucket", "a/old.txt",
                CartController.targetKey("a/old.txt", "new/old.txt"))).thenReturn(false);

        Map<String, Object> resp = controller.moveObject(
                new BucketeerController.ObjectMoveRequest(null, "bucket", "a/old.txt", "new/old.txt"),
                mock(HttpSession.class));

        assertThat(resp.get("ok")).isEqualTo(true);
        assertThat(resp.get("skipped")).isEqualTo(true);
        assertThat(duckDb.deletedKeys).isEmpty();
    }

    @Test
    @DisplayName("a successful move removes the source row from the results cache")
    void moveSuccessUpdatesCache() {
        when(useCase.moveObject("server", "bucket", "a/old.txt",
                CartController.targetKey("a/old.txt", "new/old.txt"))).thenReturn(true);

        controller.moveObject(
                new BucketeerController.ObjectMoveRequest(null, "bucket", "a/old.txt", "new/old.txt"),
                mock(HttpSession.class));

        assertThat(duckDb.deletedKeys).containsExactly("bucket", "a/old.txt");
    }

    @Test
    @DisplayName("a failed cache update after a successful delete still reports success")
    void deleteReportsOkEvenIfCacheUpdateFails() {
        duckDb.failDelete = true;

        Map<String, Object> resp = controller.deleteObject(
                new BucketeerController.ObjectDeleteRequest(null, "bucket", "k.txt"),
                mock(HttpSession.class));

        assertThat(resp.get("ok")).isEqualTo(true);
        verify(useCase).deleteObject("server", "bucket", "k.txt");
    }

    @Test
    @DisplayName("a failed S3 delete reports the error")
    void deleteReportsFailureOnS3Error() {
        org.mockito.Mockito.doThrow(new RuntimeException("Access Denied"))
                .when(useCase).deleteObject("server", "bucket", "k.txt");

        Map<String, Object> resp = controller.deleteObject(
                new BucketeerController.ObjectDeleteRequest(null, "bucket", "k.txt"),
                mock(HttpSession.class));

        assertThat(resp.get("ok")).isEqualTo(false);
        assertThat(duckDb.deletedKeys).isEmpty();
    }

    private static class RecordingDuckDb extends DuckDbRepository {
        final List<String> deletedKeys = new ArrayList<>();
        boolean failDelete;

        @Override
        public long deleteByKey(String bucket, String key) {
            if (failDelete) {
                throw new RuntimeException("cache failure");
            }
            deletedKeys.add(bucket);
            deletedKeys.add(key);
            return 1;
        }
    }

    private static class RecordingActionHistory extends ActionHistory {
        final List<ActionEntry> entries = new ArrayList<>();

        @Override
        public void append(ActionEntry entry) {
            entries.add(entry);
        }
    }
}
