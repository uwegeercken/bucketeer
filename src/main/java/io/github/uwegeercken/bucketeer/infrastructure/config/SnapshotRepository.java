package io.github.uwegeercken.bucketeer.infrastructure.config;

import io.github.uwegeercken.bucketeer.adapter.in.web.SnapshotMeta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Repository
public class SnapshotRepository {

    private static final Logger log = LoggerFactory.getLogger(SnapshotRepository.class);

    private final Path snapshotsDir;
    private final AppSettings appSettings;

    public SnapshotRepository(AppSettings appSettings) {
        this.appSettings = appSettings;
        this.snapshotsDir = Path.of(System.getProperty("user.home"), ".bucketeer", "snapshots");
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
                } catch (IOException e) {
                    log.warn("Skipping corrupt snapshot meta: {}", file.getFileName(), e);
                }
            }
        } catch (IOException e) {
            log.warn("Failed to list snapshots: {}", e.getMessage());
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
        try {
            Files.deleteIfExists(meta.metaPath(snapshotsDir));
            Files.deleteIfExists(meta.dataPath(snapshotsDir));
            return true;
        } catch (IOException e) {
            log.error("Failed to delete snapshot {}: {}", id, e.getMessage());
            return false;
        }
    }

    public int deleteExpired() {
        int retentionDays = appSettings.getSnapshotRetentionDays();
        if (retentionDays <= 0) return 0;
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        int deleted = 0;
        for (SnapshotMeta meta : findAll()) {
            if (meta.createdAt().isBefore(cutoff)) {
                if (delete(meta.id())) deleted++;
            }
        }
        return deleted;
    }

    private static boolean eq(String a, String b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.isBlank() && b.isBlank() || a.equals(b);
    }
}
