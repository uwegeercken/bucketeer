package io.github.uwegeercken.bucketeer.infrastructure.db;

import io.github.uwegeercken.bucketeer.domain.model.S3Object;
import io.github.uwegeercken.bucketeer.infrastructure.config.AppSettings;
import io.github.uwegeercken.bucketeer.infrastructure.config.TimeZoneProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

    /** Configures the repository's time zone and rebuilds the view (as the settings do). */
    private void useZone(String zoneId) {
        AppSettings settings = mock(AppSettings.class);
        when(settings.getTimeZoneId()).thenReturn(zoneId);
        repo.setTimeZoneProvider(new TimeZoneProvider(settings));
        repo.recreateViews();
    }

    /** Reads the single objects_enriched row for {@code key} as a column -> text map. */
    private Map<String, String> enrichedRow(String key) throws Exception {
        try (Statement stmt = repo.connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM objects_enriched WHERE key = '" + key + "'")) {
            assertThat(rs.next()).as("objects_enriched row for %s", key).isTrue();
            Map<String, String> row = new LinkedHashMap<>();
            for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                row.put(rs.getMetaData().getColumnName(i), rs.getString(i));
            }
            return row;
        }
    }

    @Test
    @DisplayName("objects_enriched derives the local date/time parts in the configured time zone")
    void enrichedViewDerivesLocalTimeParts() throws Exception {
        useZone("Europe/Berlin");
        // 2024-06-07 22:30:45 UTC == 2024-06-08 00:30:45 in Berlin (CEST, +2)
        repo.insertBatch(List.of(new S3Object("a/file.json", "bucket", 10L,
                Instant.parse("2024-06-07T22:30:45Z"), "etag1")));

        Map<String, String> row = enrichedRow("a/file.json");

        assertThat(row)
                .containsEntry("last_modified_local", "2024-06-08 00:30:45.0")
                .containsEntry("year", "2024")
                .containsEntry("quarter", "2")
                .containsEntry("month", "6")
                .containsEntry("week", "23")
                .containsEntry("day", "8")
                .containsEntry("day_of_week", "6")   // Saturday, ISO Monday = 1
                .containsEntry("hour", "0")
                .containsEntry("minute", "30")
                .containsEntry("second", "45");
    }

    @Test
    @DisplayName("recreateViews rebuilds the enriched view after the time zone changes")
    void recreateViewsFollowsTimeZoneChange() throws Exception {
        useZone("UTC");
        repo.insertBatch(List.of(new S3Object("k", "bucket", 1L,
                Instant.parse("2024-06-07T22:30:45Z"), "e")));
        assertThat(enrichedRow("k")).containsEntry("last_modified_local", "2024-06-07 22:30:45.0")
                .containsEntry("day", "7").containsEntry("hour", "22");

        useZone("Europe/Berlin");

        assertThat(enrichedRow("k")).containsEntry("last_modified_local", "2024-06-08 00:30:45.0")
                .containsEntry("day", "8").containsEntry("hour", "0");
    }

    @Test
    @DisplayName("objects_enriched is rebuilt when a Quack client dropped the objects table")
    void enrichedViewSurvivesDroppedTable() throws Exception {
        useZone("UTC");
        dropObjectsTable();

        repo.insertBatch(List.of(new S3Object("a/file.json", "bucket", 5L,
                Instant.parse("2024-06-07T22:30:45Z"), "e")));

        assertThat(enrichedRow("a/file.json")).containsEntry("year", "2024").containsEntry("hour", "22");
    }
}