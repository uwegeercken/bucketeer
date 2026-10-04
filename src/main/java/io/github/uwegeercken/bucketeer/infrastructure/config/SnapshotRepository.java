package io.github.uwegeercken.bucketeer.infrastructure.config;

import io.github.uwegeercken.bucketeer.adapter.in.web.SnapshotMeta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Repository
public class SnapshotRepository {

    private static final Logger log = LoggerFactory.getLogger(SnapshotRepository.class);

    private static final Pattern META_FILE = Pattern.compile("snapshot_(.+)_meta\\.json");
    private static final Pattern DATA_FILE = Pattern.compile("snapshot_(.+)_data\\.parquet");

    /** Files younger than this are treated as in-flight snapshot creation, not orphans. */
    private static final Duration ORPHAN_GRACE = Duration.ofHours(1);

    private final Path snapshotsDir;
    private final AppSettings appSettings;

    public SnapshotRepository(AppSettings appSettings) {
        this(Path.of(System.getProperty("user.home"), ".bucketeer", "snapshots"), appSettings);
    }

    /** Package-private: snapshots directory injectable for tests. */
    SnapshotRepository(Path snapshotsDir, AppSettings appSettings) {
        this.appSettings = appSettings;
        this.snapshotsDir = snapshotsDir;
        try {
            Files.createDirectories(snapshotsDir);
        } catch (IOException e) {
            throw new RuntimeException("Could not create snapshots directory: " + snapshotsDir, e);
        }
    }

    public Path getSnapshotsDir() {
        return snapshotsDir;
    }

    public void save(SnapshotMeta meta) throws IOException {
        meta.writeMeta(snapshotsDir);
    }

    public List<SnapshotMeta> findAll() {
        List<SnapshotMeta> metas = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(snapshotsDir, "*_meta.json")) {
            for (Path file : ds) {
                try {
                    metas.add(SnapshotMeta.readMeta(file));
                } catch (tools.jackson.core.JacksonException e) {
                    // Jackson 3 exceptions are runtime exceptions and are NOT caught by `catch (IOException)`.
                    log.error("Skipping corrupt snapshot meta: {}: {}", file.getFileName(), e.getMessage());
                } catch (IOException e) {
                    log.error("Skipping corrupt snapshot meta: {}: {}", file.getFileName(), e.getMessage());
                }
            }
        } catch (IOException e) {
            log.error("Failed to list snapshots: {}", e.getMessage());
        }
        metas.sort((a, b) -> b.createdAt().compareTo(a.createdAt()));
        return metas;
    }

    public SnapshotMeta findById(String id) {
        return findAll().stream()
                .filter(m -> m.id().equals(id))
                .findFirst()
                .orElse(null);
    }

    public List<SnapshotMeta> findByParams(String serverName, String bucket, String prefix,
                                             String key, String dateFrom, String dateTo,
                                             String whereClause) {
        return findAll().stream()
                .filter(m -> Objects.equals(m.serverName(), serverName)
                        && Objects.equals(m.bucket(), bucket)
                        && eq(m.prefix(), prefix)
                        && eq(m.key(), key)
                        && eq(m.dateFrom(), dateFrom)
                        && eq(m.dateTo(), dateTo)
                        && eq(m.whereClause(), whereClause))
                .toList();
    }

    public boolean delete(String id) {
        SnapshotMeta meta = findById(id);
        if (meta == null) return false;
        boolean metaGone = tryDelete(meta.metaPath(snapshotsDir), id, "meta");
        boolean dataGone = tryDelete(meta.dataPath(snapshotsDir), id, "data");
        if (metaGone && dataGone) {
            return true;
        }
        log.warn("Snapshot {} partially deleted (meta={}, data={}); orphaned file remains and is cleaned up by the next orphan sweep",
                id, metaGone, dataGone);
        return false;
    }

    private boolean tryDelete(Path path, String id, String kind) {
        try {
            Files.deleteIfExists(path);
            return true;
        } catch (IOException e) {
            log.error("Failed to delete snapshot {} {} file {}: {}", id, kind, path, e.getMessage());
            return false;
        }
    }

    public int deleteExpired() {
        int retentionDays = appSettings.getSnapshotRetentionDays();
        int deleted = 0;
        if (retentionDays > 0) {
            Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
            for (SnapshotMeta meta : findAll()) {
                if (meta.createdAt().isBefore(cutoff)) {
                    if (delete(meta.id())) deleted++;
                }
            }
            if (deleted > 0) {
                log.info("Deleted {} expired snapshot(s) (retention {} day(s))", deleted, retentionDays);
            }
        }
        removeOrphans();
        return deleted;
    }

    /** Deletes snapshot meta/data file pairs where exactly one half exists (older than the grace period). */
    private void removeOrphans() {
        Set<String> ids = new TreeSet<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(snapshotsDir)) {
            for (Path p : ds) {
                Matcher m = META_FILE.matcher(p.getFileName().toString());
                if (m.matches()) {
                    ids.add(m.group(1));
                    continue;
                }
                m = DATA_FILE.matcher(p.getFileName().toString());
                if (m.matches()) {
                    ids.add(m.group(1));
                }
            }
        } catch (IOException e) {
            log.error("Failed to scan for orphaned snapshot files: {}", e.getMessage());
            return;
        }
        for (String id : ids) {
            Path metaPath = snapshotsDir.resolve("snapshot_" + id + "_meta.json");
            Path dataPath = snapshotsDir.resolve("snapshot_" + id + "_data.parquet");
            boolean hasMeta = Files.exists(metaPath);
            boolean hasData = Files.exists(dataPath);
            if (hasMeta == hasData) continue; // complete pair — nothing orphaned
            // A fresh data file without meta may belong to an in-flight snapshot creation
            // (parquet is written before the meta). A meta without data can never be in-flight.
            if (hasData && isFresh(dataPath)) continue;
            log.warn("Orphaned snapshot file(s) for id {} (meta={}, data={}) — removing {}",
                    id, hasMeta, hasData, hasMeta ? metaPath : dataPath);
            tryDelete(metaPath, id, "orphan-meta");
            tryDelete(dataPath, id, "orphan-data");
        }
    }

    private boolean isFresh(Path path) {
        // java.io.File.lastModified() is used deliberately (long millis) so this
        // stays compilable even on JDK builds where java.nio.file.FileTime is missing.
        long lastModified = path.toFile().lastModified();
        if (lastModified <= 0) return true; // unknown state — leave the file alone
        return System.currentTimeMillis() - lastModified < ORPHAN_GRACE.toMillis();
    }

    private static boolean eq(String a, String b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.isBlank() && b.isBlank() || a.equals(b);
    }
}
