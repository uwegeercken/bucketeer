package io.github.uwegeercken.bucketeer.infrastructure.db;

import io.github.uwegeercken.bucketeer.domain.model.S3Object;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * In-memory DuckDB repository for caching S3 object listings.
 * The table is cleared on each new query and rebuilt from S3 results.
 */
@Repository
public class DuckDbRepository {

    private static final Logger log = LoggerFactory.getLogger(DuckDbRepository.class);

    private final Connection connection;

    private static boolean isValidRegex(String pattern) {
        try {
            Pattern.compile(pattern);
            return true;
        } catch (PatternSyntaxException e) {
            return false;
        }
    }

    public DuckDbRepository() {
        try {
            Class.forName("org.duckdb.DuckDBDriver");
            this.connection = DriverManager.getConnection("jdbc:duckdb:");
            initSchema();
            log.info("DuckDB in-memory database initialized");
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize DuckDB", e);
        }
    }

    private void initSchema() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS objects (
                    key           VARCHAR,
                    bucket        VARCHAR,
                    size_bytes    BIGINT,
                    last_modified TIMESTAMP,
                    etag          VARCHAR
                )
            """);
        }
    }

    /** Clears all cached objects. Called before a new query. */
    public void clear() {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("DELETE FROM objects");
        } catch (SQLException e) {
            log.error("Failed to clear objects table: {}", e.getMessage(), e);
        }
    }

    /** Inserts a batch of S3 objects into the cache. */
    public void insertBatch(List<S3Object> objects) {
        String sql = "INSERT INTO objects (key, bucket, size_bytes, last_modified, etag) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (S3Object obj : objects) {
                ps.setString(1, obj.key());
                ps.setString(2, obj.bucket());
                ps.setLong(3, obj.sizeBytes());
                ps.setTimestamp(4, obj.lastModified() != null
                        ? Timestamp.from(obj.lastModified()) : null);
                ps.setString(5, obj.etag());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            log.error("Failed to insert objects batch: {}", e.getMessage(), e);
        }
    }

    /** Returns total number of cached objects. */
    public long count() {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM objects")) {
            return rs.next() ? rs.getLong(1) : 0;
        } catch (SQLException e) {
            log.error("Failed to count objects: {}", e.getMessage(), e);
            return 0;
        }
    }

    /**
     * Queries cached objects with optional filters.
     *
     * @param keyFilter      substring filter on key (null = no filter)
     * @param minSizeKb      minimum size in KB (null = no filter)
     * @param maxSizeKb      maximum size in KB (null = no filter)
     * @param dateFrom       ISO date string yyyy-MM-dd (null = no filter)
     * @param dateTo         ISO date string yyyy-MM-dd (null = no filter)
     * @param page           0-based page number
     * @param pageSize       number of results per page
     */
    private static final java.util.Set<String> SORT_COLUMNS = java.util.Set.of("key", "size_bytes", "last_modified");
    private static final java.util.Set<String> SORT_DIRS = java.util.Set.of("asc", "desc");

    public List<S3Object> query(String keyFilter, Long minSizeKb, Long maxSizeKb,
                                String dateFrom, String dateTo,
                                int page, int pageSize,
                                String sortBy, String sortDir) {
        StringBuilder sql = new StringBuilder("SELECT key, bucket, size_bytes, last_modified, etag FROM objects WHERE 1=1");

        List<Object> params = new ArrayList<>();

        if (keyFilter != null && !keyFilter.isBlank() && isValidRegex(keyFilter)) {
            sql.append(" AND regexp_matches(key, ?)");
            params.add(keyFilter);
        }
        if (minSizeKb != null) {
            sql.append(" AND size_bytes >= ?");
            params.add(minSizeKb * 1024);
        }
        if (maxSizeKb != null) {
            sql.append(" AND size_bytes <= ?");
            params.add(maxSizeKb * 1024);
        }
        if (dateFrom != null && !dateFrom.isBlank()) {
            sql.append(" AND last_modified >= CAST(? AS TIMESTAMP)");
            params.add(dateFrom + " 00:00:00");
        }
        if (dateTo != null && !dateTo.isBlank()) {
            sql.append(" AND last_modified <= CAST(? AS TIMESTAMP)");
            params.add(dateTo + " 23:59:59");
        }

        String col = (sortBy != null && SORT_COLUMNS.contains(sortBy)) ? sortBy : "key";
        String dir = (sortDir != null && SORT_DIRS.contains(sortDir.toLowerCase())) ? sortDir.toLowerCase() : "asc";
        sql.append(" ORDER BY ").append(col).append(" ").append(dir);
        sql.append(" LIMIT ? OFFSET ?");
        params.add(pageSize);
        params.add(page * pageSize);

        List<S3Object> results = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Timestamp ts = rs.getTimestamp("last_modified");
                    results.add(new S3Object(
                            rs.getString("key"),
                            rs.getString("bucket"),
                            rs.getLong("size_bytes"),
                            ts != null ? ts.toInstant() : null,
                            rs.getString("etag")
                    ));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to query objects: {}", e.getMessage(), e);
        }
        return results;
    }

    /**
     * Exports filtered results to a Parquet file using DuckDB COPY TO.
     *
     * @param exportPath  absolute path to write the Parquet file
     * @param keyFilter   substring filter on key (null = no filter)
     * @param minSizeKb   minimum size in KB (null = no filter)
     * @param maxSizeKb   maximum size in KB (null = no filter)
     * @param dateFrom    ISO date string yyyy-MM-dd (null = no filter)
     * @param dateTo      ISO date string yyyy-MM-dd (null = no filter)
     * @return number of exported rows
     */
    public long exportToParquet(String exportPath,
                                String keyFilter, Long minSizeKb, Long maxSizeKb,
                                String dateFrom, String dateTo) {
        StringBuilder where = new StringBuilder("WHERE 1=1");
        List<Object> params = new ArrayList<>();

        if (keyFilter != null && !keyFilter.isBlank() && isValidRegex(keyFilter)) {
            where.append(" AND regexp_matches(key, ?)");
            params.add(keyFilter);
        }
        if (minSizeKb != null) {
            where.append(" AND size_bytes >= ?");
            params.add(minSizeKb * 1024);
        }
        if (maxSizeKb != null) {
            where.append(" AND size_bytes <= ?");
            params.add(maxSizeKb * 1024);
        }
        if (dateFrom != null && !dateFrom.isBlank()) {
            where.append(" AND last_modified >= CAST(? AS TIMESTAMP)");
            params.add(dateFrom + " 00:00:00");
        }
        if (dateTo != null && !dateTo.isBlank()) {
            where.append(" AND last_modified <= CAST(? AS TIMESTAMP)");
            params.add(dateTo + " 23:59:59");
        }

        // DuckDB COPY TO with parameterized subquery
        String sql = "COPY (SELECT key, bucket, size_bytes, last_modified, etag FROM objects "
                + where + " ORDER BY key) TO '" + exportPath + "' (FORMAT PARQUET)";

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            ps.execute();
            return queryCount(keyFilter, minSizeKb, maxSizeKb, dateFrom, dateTo);
        } catch (SQLException e) {
            log.error("Failed to export to Parquet: {}", e.getMessage(), e);
            throw new RuntimeException("Parquet export failed: " + e.getMessage(), e);
        }
    }
    /** Exports all current objects to a Parquet file (for snapshot creation). */
    public long exportAllToParquet(String exportPath) {
        String sql = "COPY (SELECT key, bucket, size_bytes, last_modified, etag FROM objects ORDER BY key) TO '"
                + exportPath + "' (FORMAT PARQUET)";
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
            return count();
        } catch (SQLException e) {
            log.error("Failed to export all to Parquet: {}", e.getMessage(), e);
            throw new RuntimeException("Parquet export failed: " + e.getMessage(), e);
        }
    }

    /**
     * Compares the current in-memory objects table with a snapshot parquet file.
     * Returns a DiffResult with added, removed, and changed objects.
     */
    public DiffResult diffWithSnapshot(String snapshotParquetPath) {
        try (Statement stmt = connection.createStatement()) {
            // Create temporary table from parquet
            stmt.execute("CREATE OR REPLACE TEMPORARY TABLE snapshot_data AS "
                    + "SELECT * FROM read_parquet('" + snapshotParquetPath + "')");

            // Added: in current, not in snapshot
            List<Map<String, Object>> added = queryDiff(
                    "SELECT o.key, o.bucket, o.size_bytes, o.last_modified, o.etag "
                            + "FROM objects o LEFT JOIN snapshot_data s ON o.key = s.key "
                            + "WHERE s.key IS NULL ORDER BY o.key");

            // Removed: in snapshot, not in current
            List<Map<String, Object>> removed = queryDiff(
                    "SELECT s.key, s.bucket, s.size_bytes, s.last_modified, s.etag "
                            + "FROM snapshot_data s LEFT JOIN objects o ON s.key = o.key "
                            + "WHERE o.key IS NULL ORDER BY s.key");

            // Changed: same key, different size or last_modified
            List<Map<String, Object>> changed = queryDiff(
                    "SELECT o.key, o.bucket, o.size_bytes AS new_size_bytes, o.last_modified AS new_last_modified, "
                            + "s.size_bytes AS old_size_bytes, s.last_modified AS old_last_modified "
                            + "FROM objects o INNER JOIN snapshot_data s ON o.key = s.key "
                            + "WHERE o.size_bytes != s.size_bytes "
                            + "OR o.last_modified != s.last_modified "
                            + "ORDER BY o.key");

            stmt.execute("DROP TABLE IF EXISTS snapshot_data");

            return new DiffResult(added, removed, changed);
        } catch (SQLException e) {
            log.error("Failed to compare with snapshot: {}", e.getMessage(), e);
            throw new RuntimeException("Snapshot comparison failed: " + e.getMessage(), e);
        }
    }

    private List<Map<String, Object>> queryDiff(String sql) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            ResultSetMetaData meta = rs.getMetaData();
            int colCount = meta.getColumnCount();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= colCount; i++) {
                    row.put(meta.getColumnLabel(i), rs.getObject(i));
                }
                rows.add(row);
            }
        }
        return rows;
    }

    /**
     * Exports the diff between current objects and a snapshot to a CSV file.
     * Includes a 'status' column with values: added, removed, changed.
     */
    public void exportDiffToCsv(String snapshotParquetPath, String exportPath) {
        String sql = "COPY ("
                + "SELECT 'added' AS status, o.key, o.bucket, o.size_bytes, o.last_modified, o.etag "
                + "FROM objects o LEFT JOIN read_parquet('" + snapshotParquetPath + "') s ON o.key = s.key "
                + "WHERE s.key IS NULL "
                + "UNION ALL "
                + "SELECT 'removed' AS status, s.key, s.bucket, s.size_bytes, s.last_modified, s.etag "
                + "FROM read_parquet('" + snapshotParquetPath + "') s LEFT JOIN objects o ON s.key = o.key "
                + "WHERE o.key IS NULL "
                + "UNION ALL "
                + "SELECT 'changed' AS status, o.key, o.bucket, o.size_bytes, o.last_modified, o.etag "
                + "FROM objects o INNER JOIN read_parquet('" + snapshotParquetPath + "') s ON o.key = s.key "
                + "WHERE o.size_bytes != s.size_bytes OR o.last_modified != s.last_modified "
                + "ORDER BY status, key"
                + ") TO '" + exportPath + "' (FORMAT CSV, HEADER 1)";

        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
        } catch (SQLException e) {
            log.error("Failed to export diff to CSV: {}", e.getMessage(), e);
            throw new RuntimeException("Diff export failed: " + e.getMessage(), e);
        }
    }

    public record DiffResult(
            List<Map<String, Object>> added,
            List<Map<String, Object>> removed,
            List<Map<String, Object>> changed
    ) {}

    public long queryCount(String keyFilter, Long minSizeKb, Long maxSizeKb,
                           String dateFrom, String dateTo) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM objects WHERE 1=1");
        List<Object> params = new ArrayList<>();

        if (keyFilter != null && !keyFilter.isBlank() && isValidRegex(keyFilter)) {
            sql.append(" AND regexp_matches(key, ?)");
            params.add(keyFilter);
        }
        if (minSizeKb != null) {
            sql.append(" AND size_bytes >= ?");
            params.add(minSizeKb * 1024);
        }
        if (maxSizeKb != null) {
            sql.append(" AND size_bytes <= ?");
            params.add(maxSizeKb * 1024);
        }
        if (dateFrom != null && !dateFrom.isBlank()) {
            sql.append(" AND last_modified >= CAST(? AS TIMESTAMP)");
            params.add(dateFrom + " 00:00:00");
        }
        if (dateTo != null && !dateTo.isBlank()) {
            sql.append(" AND last_modified <= CAST(? AS TIMESTAMP)");
            params.add(dateTo + " 23:59:59");
        }

        try (PreparedStatement ps = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        } catch (SQLException e) {
            log.error("Failed to count filtered objects: {}", e.getMessage(), e);
            return 0;
        }
    }
}