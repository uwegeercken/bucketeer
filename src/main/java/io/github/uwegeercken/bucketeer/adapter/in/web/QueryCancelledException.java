package io.github.uwegeercken.bucketeer.adapter.in.web;

/**
 * Thrown by the search page callback to abort a running listing when the user has
 * cancelled the query. Propagates out of {@code fetchAllObjects} (the parallel
 * variant tears its worker pool down in a finally block) and is caught by the
 * search task, which marks the query context as CANCELLED instead of ERROR.
 */
public class QueryCancelledException extends RuntimeException {

    public QueryCancelledException() {
        super("Query cancelled by user");
    }
}