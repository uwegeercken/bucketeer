package io.github.uwegeercken.bucketeer.adapter.in.web;

import io.github.uwegeercken.bucketeer.domain.model.ActionEntry;
import io.github.uwegeercken.bucketeer.domain.port.in.BucketeerUseCase;
import io.github.uwegeercken.bucketeer.domain.port.out.S3StoragePort;
import io.github.uwegeercken.bucketeer.infrastructure.db.DuckDbRepository;
import io.github.uwegeercken.bucketeer.infrastructure.history.ActionHistory;
import io.github.uwegeercken.bucketeer.infrastructure.config.AppSettings;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.multipart.MultipartFile;

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
    private S3StoragePort storage;
    private AppSettings appSettings;

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
        storage = mock(S3StoragePort.class);
        ActionHistory actionHistory = new RecordingActionHistory();
        appSettings = mock(AppSettings.class);
        when(appSettings.getMaxFileSizeMb()).thenReturn(100);
        controller = new BucketeerController(useCase, storage, sessionContext, duckDb,
                new ThreadPoolTaskExecutor(), actionHistory, appSettings);
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

    @Test
    @DisplayName("objectTags returns the tags for the selected server")
    void objectTagsReturnsTags() {
        when(useCase.getObjectTags("server", "bucket", "k.txt"))
                .thenReturn(Map.of("env", "prod"));

        Map<String, Object> resp = controller.objectTags("bucket", "k.txt");

        assertThat(resp.get("ok")).isEqualTo(true);
        assertThat(resp.get("tags")).isEqualTo(Map.of("env", "prod"));
    }

    @Test
    @DisplayName("objectTags reports an error when the S3 call fails")
    void objectTagsReportsFailureOnS3Error() {
        org.mockito.Mockito.doThrow(new RuntimeException("Access Denied"))
                .when(useCase).getObjectTags("server", "bucket", "k.txt");

        Map<String, Object> resp = controller.objectTags("bucket", "k.txt");

        assertThat(resp.get("ok")).isEqualTo(false);
        assertThat(resp.get("error")).isEqualTo("Access Denied");
    }

    @Test
    @DisplayName("uploadFile rejects a file larger than the configured max file size without calling S3")
    void uploadRejectsOversizedFile() throws Exception {
        org.mockito.Mockito.when(appSettings.getMaxFileSizeMb()).thenReturn(1);
        MultipartFile big = new org.springframework.mock.web.MockMultipartFile(
                "file", "big.bin", "application/octet-stream", new byte[2 * 1024 * 1024]);

        Map<String, Object> resp = controller.uploadFile(big, "server", "bucket", "");

        assertThat(resp.get("success")).isEqualTo(false);
        assertThat(resp.get("error").toString()).contains("exceeds the configured max file size");
        org.mockito.Mockito.verify(storage, org.mockito.Mockito.never())
                .putObject(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("clearQuery empties the cached objects and removes the query session attributes")
    void clearQueryStartsFresh() {
        HttpSession session = mock(HttpSession.class);

        Map<String, Object> resp = controller.clearQuery(session);

        assertThat(resp.get("ok")).isEqualTo(true);
        verify(session).removeAttribute(QueryContext.SESSION_KEY);
        verify(session).removeAttribute("bucketeer_query_params");
        verify(session).removeAttribute("bucketeer_snapshot_context");
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
