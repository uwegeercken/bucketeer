package io.github.uwegeercken.bucketeer.infrastructure.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuackServerTest {

    private Connection serverConnection;
    private Connection clientConnection;
    private QuackServer server;
    private boolean started;

    @AfterEach
    void tearDown() throws Exception {
        if (server != null && started) server.stop();
        if (clientConnection != null) clientConnection.close();
        if (serverConnection != null) serverConnection.close();
    }

    /** A free port from the OS. There is a tiny TOCTOU window, so start is retried. */
    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("no free port available", e);
        }
    }

    /**
     * Starts the Quack server, retrying on a fresh port a few times. The client (a second
     * DuckDB connection) must use the exact same host string as the server URI
     * ({@code localhost}): DuckDB binds only the resolved loopback family.
     */
    private void startServer() {
        for (int attempt = 0; attempt < 4 && server == null; attempt++) {
            int port = freePort();
            QuackServer candidate = new QuackServer(serverConnection, port, "test-token-1234");
            if (candidate.start()) {
                server = candidate;
                started = true;
            }
        }
        assertThat(server).as("Quack server should start on one of the probed ports").isNotNull();
    }

    @Test
    @DisplayName("a remote DuckDB connection can query the object cache over Quack")
    void remoteClientCanQueryObjects() throws Exception {
        serverConnection = DriverManager.getConnection("jdbc:duckdb:");
        try (Statement st = serverConnection.createStatement()) {
            st.execute("CREATE TABLE objects (key VARCHAR, bucket VARCHAR, size_bytes BIGINT)");
            st.execute("INSERT INTO objects VALUES ('a/1.txt', 'bucket', 42), ('b/2.txt', 'bucket', 7)");
        }
        startServer();

        clientConnection = DriverManager.getConnection("jdbc:duckdb:");
        try (Statement st = clientConnection.createStatement()) {
            st.execute("LOAD quack");
        }
        int port = portOf(server);
        String sql = "SELECT key FROM quack_query('quack:localhost:" + port
                + "', 'SELECT key FROM objects ORDER BY key', token := 'test-token-1234')";
        List<String> keys = new ArrayList<>();
        try (Statement st = clientConnection.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                keys.add(rs.getString(1));
            }
        }

        assertThat(keys).containsExactly("a/1.txt", "b/2.txt");
    }

    @Test
    @DisplayName("a wrong token is rejected by the Quack server")
    void wrongTokenIsRejected() throws Exception {
        serverConnection = DriverManager.getConnection("jdbc:duckdb:");
        startServer();

        clientConnection = DriverManager.getConnection("jdbc:duckdb:");
        try (Statement st = clientConnection.createStatement()) {
            st.execute("LOAD quack");
        }
        int port = portOf(server);
        String sql = "SELECT * FROM quack_query('quack:localhost:" + port
                + "', 'SELECT 42', token := 'wrong-token')";
        try (Statement st = clientConnection.createStatement()) {
            assertThatThrownBy(() -> st.executeQuery(sql)).isInstanceOf(Exception.class);
        }
    }

    private static int portOf(QuackServer quackServer) {
        // The port is recoverable from the URI logged/used by the server; for the test we
        // remember it via the started instance's own port.
        return quackServer.port();
    }
}