package io.github.uwegeercken.bucketeer.adapter.in.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

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

    private static final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public static SnapshotMeta create(String name, String serverName, String bucket,
                                       String prefix, String key,
                                       String dateFrom, String dateTo, String whereClause,
                                       long rowCount) {
        return new SnapshotMeta(
                UUID.randomUUID().toString().substring(0, 8),
                name,
                Instant.now(),
                serverName,
                bucket,
                prefix,
                key,
                dateFrom,
                dateTo,
                whereClause,
                rowCount
        );
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
