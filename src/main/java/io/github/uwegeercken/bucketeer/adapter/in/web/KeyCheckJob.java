package io.github.uwegeercken.bucketeer.adapter.in.web;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * State of a Key Check batch, running or finished.
 *
 * <p>Not a Spring bean - instantiated per upload and stored directly in the {@code HttpSession},
 * mirroring {@link QueryContext} for searches. The S3 existence checks run on a dedicated pool
 * while the browser polls {@code /api/keycheck/status} for progress and fetches the rows page by
 * page from {@code /api/keycheck/results}. Counting happens as rows complete, so neither the
 * status call nor the page call has to walk the whole result set.</p>
 */
public class KeyCheckJob implements Serializable {

    public static final String SESSION_KEY = "bucketeer_keycheck_job";

    public enum Status { RUNNING, DONE, ERROR, CANCELLED }

    private volatile Status status = Status.RUNNING;
    private final int total;
    private final AtomicInteger processed = new AtomicInteger();
    private final AtomicLong existCount = new AtomicLong();
    private final AtomicLong missingCount = new AtomicLong();
    private final AtomicLong errorCount = new AtomicLong();
    private volatile boolean cancelled;
    private volatile String errorMessage;
    private volatile List<Map<String, Object>> rows = List.of();
    private final Instant startedAt = Instant.now();
    private volatile Instant finishedAt;

    public KeyCheckJob(int total) {
        this.total = total;
    }

    public void incrementProcessed() { processed.incrementAndGet(); }

    public void addExists()          { existCount.incrementAndGet(); }

    public void addMissing()         { missingCount.incrementAndGet(); }

    public void addError()           { errorCount.incrementAndGet(); }

    /** Flags the running check as cancelled; the workers observe this between two keys. */
    public void cancel()             { cancelled = true; }

    /** Publishes the (possibly partial) result rows and moves the job into its terminal state. */
    public void finish(List<Map<String, Object>> rows) {
        this.rows = rows != null ? rows : List.of();
        this.status = cancelled ? Status.CANCELLED : Status.DONE;
        this.finishedAt = Instant.now();
    }

    public void fail(String message) {
        this.errorMessage = message;
        this.status = Status.ERROR;
        this.finishedAt = Instant.now();
    }

    public Status getStatus()       { return status; }

    public int getTotal()           { return total; }

    public int getProcessed()       { return processed.get(); }

    public long getExistCount()     { return existCount.get(); }

    public long getMissingCount()   { return missingCount.get(); }

    public long getErrorCount()     { return errorCount.get(); }

    public boolean isCancelled()    { return cancelled; }

    public String getErrorMessage() { return errorMessage; }

    public List<Map<String, Object>> getRows() { return rows; }

    public Instant getStartedAt()   { return startedAt; }

    public Instant getFinishedAt()  { return finishedAt; }
}
