package io.github.uwegeercken.bucketeer.adapter.in.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SnapshotMeta(
        String id,
        String name,
        Instant createdAt,
        String serverName,
        String bucket,
        String prefix,
        String key,
        String dateFrom,
        String dateTo,
        String whereClause,
        long rowCount
) implements Serializable {

    private static final ObjectMapper mapper = new ObjectMapper();

    private static final DateTimeFormatter ID_FORMAT = DateTimeFormatter.ofPattern("yyMMddHHmmss");

    public static String generateId() {
        return LocalDateTime.now().format(ID_FORMAT);
    }

    public Path metaPath(Path snapshotsDir) {
        return snapshotsDir.resolve("snapshot_" + id + "_meta.json");
    }

    public Path dataPath(Path snapshotsDir) {
        return snapshotsDir.resolve("snapshot_" + id + "_data.parquet");
    }

    public void writeMeta(Path snapshotsDir) throws IOException {
        Files.createDirectories(snapshotsDir);
        Files.writeString(metaPath(snapshotsDir), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(this));
    }

    public static SnapshotMeta readMeta(Path metaFile) throws IOException {
        return mapper.readValue(metaFile.toFile(), SnapshotMeta.class);
    }
}
