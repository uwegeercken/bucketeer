package io.github.uwegeercken.bucketeer.adapter.in.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class QueryContextTest {

    @Test
    @DisplayName("start resets the cancelled flag and returns the context to RUNNING")
    void startResetsCancelledFlag() {
        QueryContext qc = new QueryContext();
        qc.start();
        qc.cancel();
        qc.markCancelled();
        assertThat(qc.isCancelled()).isTrue();

        qc.start();

        assertThat(qc.isCancelled()).isFalse();
        assertThat(qc.getStatus()).isEqualTo(QueryContext.Status.RUNNING);
    }

    @Test
    @DisplayName("cancel flags the query without changing its status")
    void cancelOnlyFlags() {
        QueryContext qc = new QueryContext();
        qc.start();

        qc.cancel();

        assertThat(qc.isCancelled()).isTrue();
        assertThat(qc.getStatus()).isEqualTo(QueryContext.Status.RUNNING);
    }

    @Test
    @DisplayName("markCancelled is the terminal CANCELLED state, distinct from an error")
    void markCancelledSetsTerminalState() {
        QueryContext qc = new QueryContext();

        qc.markCancelled();

        assertThat(qc.getStatus()).isEqualTo(QueryContext.Status.CANCELLED);
        assertThat(qc.getErrorMessage()).isNull();
    }

    @Test
    @DisplayName("start records the start time and clears a previous finish time")
    void startRecordsAndResetsTimestamps() {
        QueryContext qc = new QueryContext();
        qc.start();
        qc.done();
        Instant firstStart = qc.getStartedAt();
        assertThat(firstStart).isNotNull();
        assertThat(qc.getFinishedAt()).isNotNull().isAfterOrEqualTo(firstStart);

        qc.start();

        assertThat(qc.getStartedAt()).isNotNull().isAfterOrEqualTo(firstStart);
        assertThat(qc.getFinishedAt()).isNull();
    }

    @Test
    @DisplayName("done records the finish time")
    void doneRecordsFinishTime() {
        QueryContext qc = new QueryContext();
        qc.start();
        Instant start = qc.getStartedAt();

        qc.done();

        assertThat(qc.getStatus()).isEqualTo(QueryContext.Status.DONE);
        assertThat(qc.getFinishedAt()).isNotNull().isAfterOrEqualTo(start);
    }
}