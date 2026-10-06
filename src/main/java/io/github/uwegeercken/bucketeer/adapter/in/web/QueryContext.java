package io.github.uwegeercken.bucketeer.adapter.in.web;

import io.github.uwegeercken.bucketeer.application.ListingReport;

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
    private volatile boolean limitReached = false;
    private volatile String errorMessage;
    private volatile ListingReport listingReport;

    public void start() {
        status = Status.RUNNING;
        objectsFound.set(0);
        limitReached = false;
        errorMessage = null;
        listingReport = null;
    }

    public void incrementFound(long count) { objectsFound.addAndGet(count); }
    public void done()                     { status = Status.DONE; }
    public void error(String message)      { status = Status.ERROR; errorMessage = message; }

    public void limitReached()             { limitReached = true; }

    public void setListingReport(ListingReport listingReport) { this.listingReport = listingReport; }

    public Status getStatus()       { return status; }
    public long getObjectsFound()   { return objectsFound.get(); }
    public boolean isLimitReached() { return limitReached; }
    public String getErrorMessage() { return errorMessage; }
    public ListingReport getListingReport() { return listingReport; }
}