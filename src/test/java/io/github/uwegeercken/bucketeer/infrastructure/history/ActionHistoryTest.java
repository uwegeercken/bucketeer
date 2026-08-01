package io.github.uwegeercken.bucketeer.infrastructure.history;

import io.github.uwegeercken.bucketeer.domain.model.ActionEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ActionHistoryTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("append and list round-trip entries, newest first")
    void appendAndList() {
        ActionHistory history = new ActionHistory(tempDir.resolve("actions.jsonl"));
        ActionEntry move = new ActionEntry(Instant.parse("2026-08-01T10:00:00Z"),
                ActionEntry.Action.MOVE, ActionEntry.Origin.RESULTS, null,
                "server1", "bucket1", "a/old.txt", "a/new.txt", ActionEntry.Status.MOVED, null);
        ActionEntry delete = new ActionEntry(Instant.parse("2026-08-01T11:00:00Z"),
                ActionEntry.Action.DELETE, ActionEntry.Origin.SELECTION, "batch1",
                "server1", "bucket1", "a/gone.txt", null, ActionEntry.Status.DELETED, null);

        history.append(move);
        history.append(delete);

        List<ActionEntry> entries = history.list();
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0)).isEqualTo(delete);
        assertThat(entries.get(1)).isEqualTo(move);
    }

    @Test
    @DisplayName("list returns empty when the log file does not exist")
    void listEmptyWhenNoFile() {
        ActionHistory history = new ActionHistory(tempDir.resolve("missing.jsonl"));
        assertThat(history.list()).isEmpty();
    }

    @Test
    @DisplayName("clear removes all entries")
    void clearRemovesFile() {
        ActionHistory history = new ActionHistory(tempDir.resolve("actions.jsonl"));
        history.append(new ActionEntry(Instant.now(), ActionEntry.Action.DELETE, ActionEntry.Origin.RESULTS,
                null, "s", "b", "k", null, ActionEntry.Status.DELETED, null));
        assertThat(history.list()).hasSize(1);

        history.clear();
        assertThat(history.list()).isEmpty();
    }
}
