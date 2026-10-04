package io.github.uwegeercken.bucketeer.infrastructure.db;

import io.github.uwegeercken.bucketeer.domain.model.S3Object;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DuckDbRepositoryTest {

    private DuckDbRepository repo;

    @BeforeEach
    void setUp() {
        repo = new DuckDbRepository();
    }

    @Test
    @DisplayName("size filter uses the rounded KB value shown in the results table")
    void sizeFilterMatchesRoundedDisplayValue() {
        // 11704 bytes = 11.4296875 KB -> displayed as 11.43 KB
        repo.insertBatch(List.of(
                new S3Object("a/file1.bin", "bucket", 11704L, Instant.now(), "etag1")
        ));

        List<S3Object> atMin = repo.query("", 11.43, null, null, null, 0, 100, "key", "asc");
        assertThat(atMin).extracting(S3Object::key).contains("a/file1.bin");

        List<S3Object> aboveRounded = repo.query("", 11.44, null, null, null, 0, 100, "key", "asc");
        assertThat(aboveRounded).isEmpty();

        List<S3Object> atMax = repo.query("", null, 11.43, null, null, 0, 100, "key", "asc");
        assertThat(atMax).extracting(S3Object::key).contains("a/file1.bin");

        assertThat(repo.queryCount("", 11.43, null, null, null)).isEqualTo(1);
    }

    @Test
    @DisplayName("a dropped objects table is recreated and refilled by the next insertBatch")
    void droppedTableIsRecoveredOnInsert() throws Exception {
        dropObjectsTable();

        repo.insertBatch(List.of(
                new S3Object("a/file1.bin", "bucket", 11704L, Instant.now(), "etag1")
        ));

        assertThat(repo.count()).isEqualTo(1);
        assertThat(repo.query("", null, null, null, null, 0, 100, "key", "asc"))
                .extracting(S3Object::key).contains("a/file1.bin");
    }

    @Test
    @DisplayName("a dropped objects table is recreated on the read path and returns an empty result")
    void droppedTableIsRecoveredOnQuery() throws Exception {
        dropObjectsTable();

        assertThat(repo.query("", null, null, null, null, 0, 100, "key", "asc")).isEmpty();
        assertThat(repo.count()).isZero();
    }

    /** Drops the cache table, as a DuckDB Quack client could. */
    private void dropObjectsTable() throws Exception {
        try (Statement stmt = repo.connection.createStatement()) {
            stmt.execute("DROP TABLE objects");
        }
    }
}