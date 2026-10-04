package io.github.uwegeercken.bucketeer.infrastructure.config;

import io.github.uwegeercken.bucketeer.adapter.in.web.SnapshotMeta;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

class SnapshotRepositoryTest {

    @TempDir
    Path tempDir;

    private SnapshotRepository newRepo(int retentionDays) throws IOException {
        Path settingsFile = tempDir.resolve("settings.json");
        Files.writeString(settingsFile, "{\"snapshotRetentionDays\": " + retentionDays + "}");
        AppSettings settings = new AppSettings(settingsFile);
        return new SnapshotRepository(tempDir.resolve("snapshots"), settings);
    }

    private SnapshotMeta meta(String id, Instant createdAt) {
        return new SnapshotMeta(id, "Snapshot " + id, createdAt,
                "srv", "bkt", "", "", null, null, null, 5L);
    }

    private void writeSnapshot(SnapshotRepository repo, SnapshotMeta m) throws IOException {
        m.writeMeta(repo.getSnapshotsDir());
        Files.writeString(m.dataPath(repo.getSnapshotsDir()), "data");
    }

    private boolean exists(SnapshotRepository repo, SnapshotMeta m, boolean data) {
        Path p = data ? m.dataPath(repo.getSnapshotsDir()) : m.metaPath(repo.getSnapshotsDir());
        return Files.exists(p);
    }

    private void age(Path p) {
        p.toFile().setLastModified(System.currentTimeMillis() - 2 * 60 * 60 * 1000L);
    }

    @Test
    @DisplayName("deleteExpired removes expired snapshots and keeps fresh ones")
    void deleteExpiredRemovesExpiredAndKeepsFresh() throws Exception {
        SnapshotRepository repo = newRepo(30);
        SnapshotMeta oldSnap   = meta("old", Instant.now().minus(40, ChronoUnit.DAYS));
        SnapshotMeta freshSnap = meta("fresh", Instant.now());
        writeSnapshot(repo, oldSnap);
        writeSnapshot(repo, freshSnap);

        int deleted = repo.deleteExpired();

        assertThat(deleted).isEqualTo(1);
        assertThat(exists(repo, oldSnap, false)).isFalse();
        assertThat(exists(repo, oldSnap, true)).isFalse();
        assertThat(exists(repo, freshSnap, false)).isTrue();
        assertThat(exists(repo, freshSnap, true)).isTrue();
    }

    @Test
    @DisplayName("deleteExpired with retention disabled keeps snapshots but still sweeps orphans")
    void deleteExpiredWithRetentionDisabledSweepsOrphansButKeepsSnapshots() throws Exception {
        SnapshotRepository repo = newRepo(0);
        SnapshotMeta oldSnap = meta("old", Instant.now().minus(40, ChronoUnit.DAYS));
        writeSnapshot(repo, oldSnap);

        Path orphanData = repo.getSnapshotsDir().resolve("snapshot_orphan_data.parquet");
        Files.writeString(orphanData, "orphan");
        age(orphanData);

        int deleted = repo.deleteExpired();

        assertThat(deleted).isZero();
        assertThat(exists(repo, oldSnap, false)).isTrue();
        assertThat(exists(repo, oldSnap, true)).isTrue();
        assertThat(Files.exists(orphanData)).isFalse();
    }

    @Test
    @DisplayName("orphan sweep keeps fresh lone data files (in-flight) and removes meta orphans")
    void orphanSweepKeepsFreshAndRemovesOld() throws Exception {
        SnapshotRepository repo = newRepo(30);
        // In-flight creation: parquet exists, meta not yet written -> fresh -> kept
        Path freshData = repo.getSnapshotsDir().resolve("snapshot_inflight_data.parquet");
        Files.writeString(freshData, "fresh data");

        // Orphan: valid meta without data -> removed (never an in-flight state)
        SnapshotMeta leftover = meta("leftover", Instant.now());
        leftover.writeMeta(repo.getSnapshotsDir());

        repo.deleteExpired();

        assertThat(Files.exists(freshData)).isTrue();
        assertThat(exists(repo, leftover, false)).isFalse();
    }

    @Test
    @DisplayName("delete removes meta and data files; unknown id returns false")
    void deleteRemovesBothFilesAndReturnsFalseForUnknownId() throws Exception {
        SnapshotRepository repo = newRepo(30);
        SnapshotMeta snap = meta("del", Instant.now());
        writeSnapshot(repo, snap);

        assertThat(repo.delete(snap.id())).isTrue();
        assertThat(exists(repo, snap, false)).isFalse();
        assertThat(exists(repo, snap, true)).isFalse();
        assertThat(repo.delete("missing")).isFalse();
    }
}