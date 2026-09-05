package io.github.uwegeercken.bucketeer.infrastructure.db;

import io.github.uwegeercken.bucketeer.domain.model.S3Object;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
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
}