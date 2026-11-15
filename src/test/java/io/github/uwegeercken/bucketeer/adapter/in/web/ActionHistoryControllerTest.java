package io.github.uwegeercken.bucketeer.adapter.in.web;

import io.github.uwegeercken.bucketeer.domain.model.ActionEntry;
import io.github.uwegeercken.bucketeer.domain.port.in.BucketeerUseCase;
import io.github.uwegeercken.bucketeer.infrastructure.config.TimeZoneProvider;
import io.github.uwegeercken.bucketeer.infrastructure.db.DuckDbRepository;
import io.github.uwegeercken.bucketeer.infrastructure.history.ActionHistory;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ActionHistoryControllerTest {

    private BucketeerUseCase useCase;
    private ActionHistory actionHistory;
    private DuckDbRepository duckDb;
    private ActionHistoryController controller;

    @BeforeEach
    void setUp() {
        useCase = mock(BucketeerUseCase.class);
        actionHistory = mock(ActionHistory.class);
        duckDb = mock(DuckDbRepository.class);
        when(useCase.serverNames()).thenReturn(List.of("srv"));
        controller = new ActionHistoryController(actionHistory, new ObjectMapper(),
                mock(TimeZoneProvider.class), useCase, duckDb);
    }

    private ActionEntry lastEntry() {
        ArgumentCaptor<ActionEntry> captor = ArgumentCaptor.forClass(ActionEntry.class);
        verify(actionHistory).append(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("undoMove moves the object from the target back to its original key and logs it")
    void undoMoveMovesBack() {
        when(useCase.moveObject("srv", "bucket", "new/a.txt", "old/a.txt")).thenReturn(true);

        Map<String, Object> resp = controller.undoMove(
                new ActionHistoryController.UndoRequest("srv", "bucket", "old/a.txt", "new/a.txt"),
                mock(HttpSession.class));

        assertThat(resp.get("ok")).isEqualTo(true);
        assertThat(resp.get("skipped")).isEqualTo(false);
        verify(duckDb).deleteByKey("bucket", "new/a.txt");
        ActionEntry entry = lastEntry();
        assertThat(entry.action()).isEqualTo(ActionEntry.Action.MOVE);
        assertThat(entry.status()).isEqualTo(ActionEntry.Status.MOVED);
        assertThat(entry.sourceKey()).isEqualTo("new/a.txt");
        assertThat(entry.targetKey()).isEqualTo("old/a.txt");
    }

    @Test
    @DisplayName("undoMove restores objects that originally lived in the bucket root")
    void undoMoveRestoresRootObject() {
        when(useCase.moveObject("srv", "bucket", "new/a.txt", "a.txt")).thenReturn(true);

        Map<String, Object> resp = controller.undoMove(
                new ActionHistoryController.UndoRequest("srv", "bucket", "a.txt", "new/a.txt"),
                mock(HttpSession.class));

        assertThat(resp.get("ok")).isEqualTo(true);
        verify(useCase).moveObject("srv", "bucket", "new/a.txt", "a.txt");
    }

    @Test
    @DisplayName("undoMove skips without overwriting when the original key is occupied")
    void undoMoveSkipsWhenOriginalExists() {
        when(useCase.moveObject("srv", "bucket", "new/a.txt", "old/a.txt")).thenReturn(false);

        Map<String, Object> resp = controller.undoMove(
                new ActionHistoryController.UndoRequest("srv", "bucket", "old/a.txt", "new/a.txt"),
                mock(HttpSession.class));

        assertThat(resp.get("ok")).isEqualTo(true);
        assertThat(resp.get("skipped")).isEqualTo(true);
        verify(duckDb, never()).deleteByKey(anyString(), anyString());
        assertThat(lastEntry().status()).isEqualTo(ActionEntry.Status.SKIPPED);
    }

    @Test
    @DisplayName("undoMove reports and logs a failed move, e.g. when the object no longer exists")
    void undoMoveReportsFailure() {
        when(useCase.moveObject("srv", "bucket", "new/a.txt", "old/a.txt"))
                .thenThrow(new RuntimeException("NoSuchKey"));

        Map<String, Object> resp = controller.undoMove(
                new ActionHistoryController.UndoRequest("srv", "bucket", "old/a.txt", "new/a.txt"),
                mock(HttpSession.class));

        assertThat(resp.get("ok")).isEqualTo(false);
        assertThat(resp.get("error")).isEqualTo("NoSuchKey");
        ActionEntry entry = lastEntry();
        assertThat(entry.status()).isEqualTo(ActionEntry.Status.FAILED);
        assertThat(entry.error()).isEqualTo("NoSuchKey");
    }

    @Test
    @DisplayName("undoMove rejects incomplete requests and unknown servers without touching S3")
    void undoMoveValidatesInput() {
        Map<String, Object> missing = controller.undoMove(
                new ActionHistoryController.UndoRequest("srv", "bucket", "old/a.txt", ""),
                mock(HttpSession.class));
        Map<String, Object> unknown = controller.undoMove(
                new ActionHistoryController.UndoRequest("other", "bucket", "old/a.txt", "new/a.txt"),
                mock(HttpSession.class));

        assertThat(missing.get("ok")).isEqualTo(false);
        assertThat(unknown.get("ok")).isEqualTo(false);
        verify(useCase, never()).moveObject(anyString(), anyString(), anyString(), anyString());
        verify(actionHistory, never()).append(any());
    }
}
