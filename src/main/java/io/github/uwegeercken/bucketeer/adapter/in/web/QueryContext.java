package io.github.uwegeercken.bucketeer.adapter.in.web;

import java.io.Serializable;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Holds the state of the current S3 listing query.
 * Not a Spring bean - instantiated per query and stored directly in HttpSession.
 */
public class QueryContext implements Serializable {

    public static final String SESSION_KEY = "bucketeer_query_context";

    public enum Status { IDLE, RUNNING, DONE, ERROR }

    private volatile Status status = Status.IDLE;
    private final AtomicLong objectsFound = new AtomicLong(0);
    private volatile String errorMessage;

    public void start() {
        status = Status.RUNNING;
        objectsFound.set(0);
        errorMessage = null;
    }

    public void incrementFound(long count) { objectsFound.addAndGet(count); }
    public void done()                     { status = Status.DONE; }
    public void error(String message)      { status = Status.ERROR; errorMessage = message; }

    public Status getStatus()       { return status; }
    public long getObjectsFound()   { return objectsFound.get(); }
    public String getErrorMessage() { return errorMessage; }
}