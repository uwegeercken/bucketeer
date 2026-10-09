package io.github.uwegeercken.bucketeer.application;

import java.io.Serializable;
import java.time.Instant;

/**
 * Describes how one listing query was executed: which listing strategy (sequential vs.
 * parallel) was chosen, with how many workers, and - when a prefix analysis ran - how
 * the distribution was measured. Produced inside {@link BucketeerService#fetchAllObjects}
 * in every branch and handed to an optional report consumer, which the session search
 * path uses to surface the decision in the UI.
 *
 * <p>A report is always created - even for scopes that never reach the analysis - so the
 * UI can explain <em>why</em> a query ran sequentially (parallelism disabled, a single
 * top-level prefix, or a fallback after a failed analysis).
 *
 * @param decision        which strategy was chosen and why
 * @param workers         effective worker count considered for the listing
 *                        (1 when parallelism is disabled)
 * @param medianObjects   median objects per sampled sub prefix, only when an analysis ran
 * @param sampleCount     number of sampled sub prefixes, only when an analysis ran
 * @param levelPrefixes   top-level common prefixes seen on the first level page,
 *                        only when an analysis ran
 * @param analysisCached  whether the analysis was served from the cache instead of
 *                        sampling again, only when an analysis ran
 */
public record ListingReport(
        String serverName,
        String bucket,
        String prefix,
        Instant searchedAt,
        int workers,
        Decision decision,
        Integer medianObjects,
        Integer sampleCount,
        Integer levelPrefixes,
        Boolean analysisCached
) implements Serializable {

    /** Why the query ran the way it did. */
    public enum Decision {
        /** The analysis measured a distribution where parallel listing is expected to win. */
        PARALLEL,
        /** The analysis measured a distribution where one flat stream is expected to win. */
        SEQUENTIAL_BALANCED,
        /** The scope has at most one top-level prefix; a parallel split cannot help. */
        SEQUENTIAL_NO_SPLIT,
        /** Query parallelism is disabled (configured below 2); no analysis ran. */
        SEQUENTIAL_PARALLELISM,
        /** Level scan or analysis failed; the query fell back to a sequential listing. */
        SEQUENTIAL_FALLBACK
    }

    /** Whether the listing actually ran in parallel. */
    public boolean parallel() {
        return decision == Decision.PARALLEL;
    }

    /** Whether a prefix analysis produced the numbers in this report. */
    public boolean hasAnalysis() {
        return medianObjects != null && sampleCount != null && levelPrefixes != null;
    }
}