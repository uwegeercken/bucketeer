package io.github.uwegeercken.bucketeer.application;

import io.github.uwegeercken.bucketeer.domain.model.LevelListing;
import io.github.uwegeercken.bucketeer.domain.model.ObjectListing;
import io.github.uwegeercken.bucketeer.domain.model.PrefixCount;
import io.github.uwegeercken.bucketeer.domain.model.PrefixScan;
import io.github.uwegeercken.bucketeer.domain.port.in.BucketeerUseCase;
import io.github.uwegeercken.bucketeer.domain.port.out.S3StoragePort;
import io.github.uwegeercken.bucketeer.domain.template.PrefixTemplateEngine;
import io.github.uwegeercken.bucketeer.infrastructure.config.AppSettings;
import io.github.uwegeercken.bucketeer.infrastructure.config.S3Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@Service
public class BucketeerService implements BucketeerUseCase {

    private static final Logger log = LoggerFactory.getLogger(BucketeerService.class);

    public static final int DEFAULT_SCAN_MAX_PREFIXES = 200;
    public static final int DEFAULT_SCAN_MAX_DEPTH = 10;

    /** Page size used for level scans and per-prefix harvesting (S3 maxKeys cap). Also the
     *  page size assumed by {@link PrefixProfile#prefersParallel(int)}. */
    static final int LISTING_PAGE = 1000;

    /** Upper bound for parallel harvesters regardless of the configured parallelism. */
    private static final int MAX_PARALLEL_WORKERS = 32;

    private final S3StoragePort s3StoragePort;
    private final PrefixTemplateEngine templateEngine;
    private final S3Properties s3Properties;
    private final PrefixAnalyzer prefixAnalyzer;
    private final AppSettings appSettings;

    public BucketeerService(S3StoragePort s3StoragePort, PrefixTemplateEngine templateEngine,
                            S3Properties s3Properties, PrefixAnalyzer prefixAnalyzer,
                            AppSettings appSettings) {
        this.s3StoragePort = s3StoragePort;
        this.templateEngine = templateEngine;
        this.s3Properties = s3Properties;
        this.prefixAnalyzer = prefixAnalyzer;
        this.appSettings = appSettings;
    }

    @Override
    public List<String> serverNames() {
        return s3StoragePort.serverNames();
    }

    @Override
    public List<String> listBuckets(String serverName) {
        return s3StoragePort.listBuckets(serverName);
    }

    @Override
    public List<String> availableFunctions() {
        return templateEngine.availableFunctions();
    }

    @Override
    public String resolveTemplate(String template, String key, String bucket) {
        return templateEngine.resolve(template, key, bucket);
    }

    @Override
    public List<String> validateTemplate(String template) {
        return templateEngine.validate(template);
    }

    @Override
    public ObjectListing listObjects(String serverName, String bucket, String resolvedPrefix, String continuationToken) {
        return s3StoragePort.listObjects(serverName, bucket, resolvedPrefix, continuationToken, 0);
    }

    @Override
    public PrefixScan scanPrefixes(String serverName, String bucket, String prefix,
                                   long maxPrefixes, String continuationToken) {
        int configMaxPrefixes = s3Properties.scan().maxPrefixes();
        int configMaxDepth = s3Properties.scan().maxDepth();

        String normalizedPrefix = prefix != null ? prefix : "";
        if (configMaxDepth > 0 && countDepth(normalizedPrefix) > configMaxDepth) {
            throw new IllegalArgumentException(
                    "Scan depth exceeds configured maximum of " + configMaxDepth + " levels");
        }

        long limit = maxPrefixes;
        if (limit <= 0) {
            limit = configMaxPrefixes > 0 ? configMaxPrefixes : DEFAULT_SCAN_MAX_PREFIXES;
        }
        if (configMaxPrefixes > 0) {
            limit = Math.min(limit, configMaxPrefixes);
        }

        PrefixScan scan = s3StoragePort.scanCommonPrefixes(serverName, bucket, normalizedPrefix, continuationToken, limit);
        log.debug("Scan {}/{} '{}': found {} prefix(es){}", serverName, bucket,
                normalizedPrefix,
                scan != null ? scan.prefixes().size() : 0,
                scan != null && scan.truncated() ? " (truncated, more available)" : "");
        return scan;
    }

    private static int countDepth(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return 0;
        }
        int depth = 0;
        for (int i = 0; i < prefix.length(); i++) {
            if (prefix.charAt(i) == '/') {
                depth++;
            }
        }
        return depth;
    }

    @Override
    public List<String> countPrefixes(String serverName, String bucket, List<String> prefixes) {
        if (prefixes == null || prefixes.isEmpty()) {
            return List.of();
        }
        List<PrefixCount> counts = prefixes.stream().parallel()
                .map(p -> {
                    try {
                        return s3StoragePort.countCommonPrefixes(serverName, bucket, p == null ? "" : p);
                    } catch (Exception e) {
                        log.debug("Failed to count sub-prefixes under {}/{} '{}': {}",
                                serverName, bucket, p, e.getMessage());
                        return null;
                    }
                })
                .toList();
        return counts.stream()
                .map(c -> c == null ? "" : (c.capped() ? c.count() + "+" : String.valueOf(c.count())))
                .toList();
    }

    @Override
    public boolean fetchAllObjects(String serverName, String bucket, String resolvedPrefix,
                                  long maxObjects, Consumer<ObjectListing> pageCallback) {
        int parallelism = appSettings.getQueryParallelism();
        if (parallelism < 2) {
            return fetchAllObjectsSequential(serverName, bucket, resolvedPrefix, maxObjects, pageCallback);
        }

        LevelListing first;
        try {
            first = s3StoragePort.listObjectsWithLevel(serverName, bucket, resolvedPrefix, null, LISTING_PAGE);
        } catch (RuntimeException e) {
            log.warn("Level scan for {}/{} '{}' failed ({}); using a sequential listing",
                    serverName, bucket, resolvedPrefix, e.getMessage());
            return fetchAllObjectsSequential(serverName, bucket, resolvedPrefix, maxObjects, pageCallback);
        }

        if (first.commonPrefixes() == null || first.commonPrefixes().size() <= 1) {
            return fetchAllObjectsSequential(serverName, bucket, resolvedPrefix, maxObjects, pageCallback);
        }

        int workers = Math.max(1, Math.min(parallelism, MAX_PARALLEL_WORKERS));
        boolean parallel;
        try {
            PrefixProfile profile = prefixAnalyzer.profileFor(serverName, bucket, resolvedPrefix,
                    first.commonPrefixes(), appSettings.getQuerySampleSize(), workers);
            parallel = profile.prefersParallel(workers);
            log.debug("Listing tactic for {}/{} '{}': {} (median {} object(s) per prefix, "
                            + "{} sample(s), {} top-level prefix(es), {} worker(s))",
                    serverName, bucket, resolvedPrefix, parallel ? "parallel" : "sequential",
                    profile.medianObjects(), profile.sampleCount(), profile.levelPrefixes(), workers);
        } catch (RuntimeException e) {
            log.warn("Prefix analysis for {}/{} '{}' failed ({}); using a sequential listing",
                    serverName, bucket, resolvedPrefix, e.getMessage());
            parallel = false;
        }
        if (!parallel) {
            return fetchAllObjectsSequential(serverName, bucket, resolvedPrefix, maxObjects, pageCallback);
        }
        return fetchAllObjectsParallel(serverName, bucket, resolvedPrefix, maxObjects, pageCallback, first, workers);
    }

    /**
     * Sequential, single-stream listing (ListObjectsV2 continuation token). Used for flat
     * buckets and single-prefix scopes where a parallel split cannot help.
     */
    private boolean fetchAllObjectsSequential(String serverName, String bucket, String resolvedPrefix,
                                              long maxObjects, Consumer<ObjectListing> pageCallback) {
        String token = null;
        long count = 0;
        do {
            long remaining = maxObjects > 0 ? Math.max(0, maxObjects - count) : 0;
            ObjectListing page = s3StoragePort.listObjects(serverName, bucket, resolvedPrefix, token,
                    maxObjects > 0 ? Math.min(remaining, LISTING_PAGE) : 0);
            pageCallback.accept(page);
            count += page.objects().size();
            token = page.truncated() ? page.nextContinuationToken() : null;
            if (maxObjects > 0 && count >= maxObjects) {
                return true;
            }
        } while (token != null);
        return false;
    }

    /**
     * Parallel listing for scopes with multiple top-level common prefixes.
     *
     * The current thread walks the delimiter ("/") level, feeding the objects it sees into a queue and
     * handing every common prefix to a bounded set of workers. Each worker lists its prefix recursively
     * (own continuation token) and puts the pages into the same queue. The {@link Consumer} is only ever
     * invoked from this thread (single-writer), so callers that mutate shared state stay safe. A shared
     * stop flag keeps the listing from running on after {@code maxObjects} is reached.
     * The workers run on a dedicated fixed pool of exactly {@code workers} daemon threads
     * ("s3-list-*") that is torn down when the listing ends, so the configured worker
     * count is honoured regardless of any other executor in the application.
     */
    private boolean fetchAllObjectsParallel(String serverName, String bucket, String resolvedPrefix,
                                            long maxObjects, Consumer<ObjectListing> pageCallback,
                                            LevelListing first, int workers) {
        BlockingQueue<ObjectListing> pages = new LinkedBlockingQueue<>();
        BlockingQueue<String> work = new LinkedBlockingQueue<>();
        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicBoolean workDone = new AtomicBoolean(false);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(workers);

        Runnable worker = () -> {
            try {
                while (!stop.get() && failure.get() == null) {
                    String subPrefix;
                    try {
                        subPrefix = work.poll(200, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    if (subPrefix == null) {
                        if (workDone.get()) {
                            break;
                        }
                        continue;
                    }
                    try {
                        harvestPrefix(serverName, bucket, subPrefix, pages, stop);
                    } catch (Throwable t) {
                        failure.compareAndSet(null, t);
                        stop.set(true);
                        break;
                    }
                }
            } finally {
                done.countDown();
            }
        };
        // A dedicated pool per listing guarantees that exactly `workers` threads run: a
        // shared pool with core/max/queue sizing creates threads beyond the core size only
        // once its queue is full, which silently under-provisions the harvesters.
        AtomicInteger threadSeq = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(workers, runnable -> {
            Thread thread = new Thread(runnable, "s3-list-" + threadSeq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });

        long found = 0;
        boolean limitReached = false;
        LevelListing level = first;
        try {
            for (int i = 0; i < workers; i++) {
                pool.execute(worker);
            }

            while (level != null && !stop.get() && failure.get() == null) {
                for (String subPrefix : level.commonPrefixes()) {
                    work.offer(subPrefix);
                }
                pages.offer(new ObjectListing(level.objects(), null, false));
                found = drainAvailable(pages, pageCallback, found, maxObjects, stop);
                if (maxObjects > 0 && found >= maxObjects) {
                    limitReached = true;
                    stop.set(true);
                    break;
                }
                level = needsMore(level)
                        ? s3StoragePort.listObjectsWithLevel(serverName, bucket, resolvedPrefix,
                        level.nextContinuationToken(), LISTING_PAGE)
                        : null;
            }
            workDone.set(true);

            while (!stop.get() && done.getCount() > 0) {
                found = drainAvailable(pages, pageCallback, found, maxObjects, stop);
                if (maxObjects > 0 && found >= maxObjects) {
                    limitReached = true;
                    stop.set(true);
                    break;
                }
                try {
                    if (done.await(200, TimeUnit.MILLISECONDS)) {
                        break;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Parallel object listing interrupted", e);
                }
            }
            // workers can finish before the first drain inside the loop ever sees their
            // pages, so the limit has to be evaluated after the final drain as well
            found = drainAvailable(pages, pageCallback, found, maxObjects, stop);
            if (maxObjects > 0 && found >= maxObjects) {
                limitReached = true;
                stop.set(true);
            }
        } finally {
            stop.set(true);
            pool.shutdownNow();
        }

        if (failure.get() != null) {
            throw new RuntimeException(
                    "Parallel object listing failed: " + failure.get().getMessage(), failure.get());
        }
        log.debug("Parallel listing {}/{} '{}' completed: {} object(s){}",
                serverName, bucket, resolvedPrefix, found, limitReached ? " (limit reached)" : "");
        return limitReached;
    }

    private void harvestPrefix(String serverName, String bucket, String subPrefix,
                               BlockingQueue<ObjectListing> pages, AtomicBoolean stop) {
        String token = null;
        do {
            if (stop.get()) {
                return;
            }
            ObjectListing page = s3StoragePort.listObjects(serverName, bucket, subPrefix, token, LISTING_PAGE);
            pages.offer(page);
            token = page.truncated() ? page.nextContinuationToken() : null;
        } while (token != null);
    }

    private static boolean needsMore(LevelListing level) {
        return level != null && level.truncated()
                && level.nextContinuationToken() != null && !level.nextContinuationToken().isBlank();
    }

    private static long drainAvailable(BlockingQueue<ObjectListing> pages, Consumer<ObjectListing> pageCallback,
                                       long found, long maxObjects, AtomicBoolean stop) {
        if (stop.get()) {
            while (pages.poll() != null) {
            }
            return found;
        }
        ObjectListing page;
        while ((page = pages.poll()) != null) {
            if (maxObjects > 0 && found >= maxObjects) {
                break;
            }
            long remaining = maxObjects > 0 ? maxObjects - found : Long.MAX_VALUE;
            if (page.objects().size() > remaining) {
                page = new ObjectListing(page.objects().subList(0, (int) remaining), null, false);
            }
            pageCallback.accept(page);
            found += page.objects().size();
            if (maxObjects > 0 && found >= maxObjects) {
                break;
            }
        }
        return found;
    }

    @Override
    public boolean moveObject(String serverName, String bucket, String sourceKey, String targetKey) {
        if (targetKey == null || targetKey.isBlank()) {
            throw new IllegalArgumentException("Target key must not be empty");
        }
        if (targetKey.equals(sourceKey)) {
            throw new IllegalArgumentException("Target key must differ from source key");
        }
        boolean copied = s3StoragePort.copyObject(serverName, bucket, sourceKey, bucket, targetKey, false);
        if (!copied) {
            return false;
        }
        try {
            s3StoragePort.deleteObject(serverName, bucket, sourceKey);
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "Copy created at " + targetKey + " but deletion of " + sourceKey + " failed ("
                            + e.getMessage() + "). A duplicate may exist - remove it manually once permitted.",
                    e);
        }
        return true;
    }

    @Override
    public void deleteObject(String serverName, String bucket, String key) {
        s3StoragePort.deleteObject(serverName, bucket, key);
    }

    @Override
    public Map<String, String> getObjectTags(String serverName, String bucket, String key) {
        return s3StoragePort.getObjectTags(serverName, bucket, key);
    }
}