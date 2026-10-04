package io.github.uwegeercken.bucketeer.infrastructure.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;

/**
 * Exposes the process-wide in-memory DuckDB over the Quack remote protocol so that
 * another DuckDB process (e.g. the CLI in a terminal) can query the cached results.
 *
 * <p>Only localhost is ever bound ({@code allow_other_hostname} is never passed), so
 * DuckDB itself refuses any other hostname. Authentication is token-based; the token
 * is generated at start (or taken from configuration) and logged together with a
 * ready-to-use ATTACH snippet.
 *
 * <p>Quack is a beta protocol (duckdb.org/docs/current/quack): function names and
 * defaults may change between DuckDB versions. Bucketeer pins the DuckDB JDBC version
 * in pom.xml, which absorbs most of these changes.
 */
public class QuackServer {

    private static final Logger log = LoggerFactory.getLogger(QuackServer.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Connection connection;
    private final int port;
    private final String token;

    public QuackServer(Connection connection, int port, String token) {
        this.connection = connection;
        this.port = port;
        this.token = token != null && !token.isBlank() ? token : generateToken();
    }

    public String token() {
        return token;
    }

    public int port() {
        return port;
    }

    /**
     * Starts the Quack server on localhost. Returns false (and logs a warning) when the
     * Quack extension is unavailable or the server could not be started; the application
     * keeps running either way.
     */
    public boolean start() {
        String uri = "quack:localhost:" + port;
        try {
            loadQuack();
        } catch (SQLException e) {
            log.warn("DuckDB Quack extension not available, remote SQL access disabled: {}", e.getMessage());
            return false;
        }
        try (Statement st = connection.createStatement()) {
            // Never pass allow_other_hostname -> DuckDB restricts the server to localhost.
            st.execute("CALL quack_serve('" + uri + "', token := '" + token + "')");
        } catch (SQLException e) {
            log.error("Failed to start DuckDB Quack server on {}: {}", uri, e.getMessage());
            return false;
        }
        log.info("DuckDB Quack server listening on {} (token: {})", uri, token);
        log.info("Connect from another DuckDB process: ATTACH '{}' AS bucketeer (TOKEN '{}');", uri, token);
        log.info("Query samples: FROM bucketeer.objects; FROM bucketeer.query('show tables'); FROM bucketeer.query('describe objects');");
        return true;
    }

    /** Stops the Quack server. Best effort - failures are only logged. */
    public void stop() {
        String uri = "quack:localhost:" + port;
        try (Statement st = connection.createStatement()) {
            st.execute("CALL quack_stop('" + uri + "')");
            log.info("DuckDB Quack server stopped ({})", uri);
        } catch (SQLException e) {
            log.warn("Failed to stop DuckDB Quack server on {}: {}", uri, e.getMessage());
        }
    }

    private void loadQuack() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("LOAD quack");
        }
    }

    private static String generateToken() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}