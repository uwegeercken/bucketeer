package io.github.uwegeercken.bucketeer.adapter.in.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
}