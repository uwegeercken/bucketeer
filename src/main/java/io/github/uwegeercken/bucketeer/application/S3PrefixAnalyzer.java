package io.github.uwegeercken.bucketeer.application;

import io.github.uwegeercken.bucketeer.domain.model.ObjectListing;
import io.github.uwegeercken.bucketeer.domain.port.out.S3StoragePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Samples sub prefixes through {@link S3StoragePort} and caches the resulting
 * {@link PrefixProfile} per {@code server|bucket|prefix} for a short time.
 *
 * <p>The analysis is deliberately bounded: it never walks all prefixes, it only draws
 * {@code samples} entries from the first level page (which the parallel listing needs
 * anyway) and lists each of them once with the normal page cap. The samples run
 * concurrently, so the wall-clock cost is roughly one round trip on a cache miss and
 * nothing at all while the profile is fresh.
 */
@Service
public class S3PrefixAnalyzer implements PrefixAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(S3PrefixAnalyzer.class);

    /** How long a measured profile is reused before the scope is sampled again. */
    static final Duration TTL = Duration.ofMinutes(15);

    /** Upper bound for cached scopes; expired entries are dropped first, then the cache. */
    private static final int MAX_CACHE_ENTRIES = 256;

    private final S3StoragePort s3StoragePort;
    private final Supplier<Instant> clock;
    private final Map<String, CachedProfile> cache = new ConcurrentHashMap<>();

    private record CachedProfile(PrefixProfile profile, Instant cachedAt) {
    }

    /** The single constructor Spring uses; the two-arg variant is for tests only. */
    @Autowired
    public S3PrefixAnalyzer(S3StoragePort s3StoragePort) {
        this(s3StoragePort, Instant::now);
    }

    /** Package-private: clock injectable for tests. */
    S3PrefixAnalyzer(S3StoragePort s3StoragePort, Supplier<Instant> clock) {
        this.s3StoragePort = s3StoragePort;
        this.clock = clock;
    }

    @Override
    public PrefixProfile profileFor(String serverName, String bucket, String prefix,
                                    List<String> levelPrefixes, int samples, int parallelism) {
        int wanted = Math.max(1, Math.min(samples, levelPrefixes.size()));
        String key = cacheKey(serverName, bucket, prefix);
        CachedProfile cached = cache.get(key);
        if (cached != null && cached.profile().sampleCount() >= wanted
                && !cached.cachedAt().plus(TTL).isBefore(clock.get())) {
            log.debug("Prefix analysis cache hit for {}/{} '{}' ({} sample(s))",
                    serverName, bucket, prefix, cached.profile().sampleCount());
            return cached.profile();
        }
        PrefixProfile profile = sample(serverName, bucket, levelPrefixes, wanted, parallelism);
        cache.put(key, new CachedProfile(profile, clock.get()));
        trimCache();
        return profile;
    }

    private PrefixProfile sample(String serverName, String bucket, List<String> levelPrefixes,
                                 int count, int parallelism) {
        List<String> chosen = chooseRandom(levelPrefixes, count);
        int poolSize = Math.max(1, Math.min(parallelism, chosen.size()));
        AtomicInteger threadSeq = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(poolSize, runnable -> {
            Thread thread = new Thread(runnable, "s3-analyze-" + threadSeq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        try {
            List<Future<PrefixProfile.Sample>> futures = new ArrayList<>(chosen.size());
            for (String subPrefix : chosen) {
                futures.add(pool.submit(() -> sampleOne(serverName, bucket, subPrefix)));
            }
            List<PrefixProfile.Sample> results = new ArrayList<>(futures.size());
            for (Future<PrefixProfile.Sample> future : futures) {
                results.add(future.get());
            }
            PrefixProfile profile = PrefixProfile.of(levelPrefixes.size(), results, clock.get());
            log.debug("Prefix analysis for {}/{} sampled {} of {} prefix(es): median {} object(s)",
                    serverName, bucket, profile.sampleCount(), levelPrefixes.size(),
                    profile.medianObjects());
            return profile;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new RuntimeException("Prefix sampling failed: " + cause.getMessage(), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Prefix sampling interrupted", e);
        } finally {
            pool.shutdownNow();
        }
    }

    private PrefixProfile.Sample sampleOne(String serverName, String bucket, String subPrefix) {
        int pageCap = BucketeerService.LISTING_PAGE;
        long start = System.nanoTime();
        ObjectListing page = s3StoragePort.listObjects(serverName, bucket, subPrefix, null, pageCap);
        long durationMs = (System.nanoTime() - start) / 1_000_000;
        // A truncated page only tells us "at least page + 1 objects" - a lower bound that
        // keeps the decision on the safe side.
        int objects = page.truncated() ? pageCap + 1 : Math.min(page.objects().size(), pageCap);
        return new PrefixProfile.Sample(subPrefix, objects, page.truncated(), durationMs);
    }

    private static List<String> chooseRandom(List<String> levelPrefixes, int count) {
        if (count >= levelPrefixes.size()) {
            return List.copyOf(levelPrefixes);
        }
        List<String> shuffled = new ArrayList<>(levelPrefixes);
        Collections.shuffle(shuffled);
        return shuffled.subList(0, count);
    }

    private static String cacheKey(String serverName, String bucket, String prefix) {
        return serverName + '|' + bucket + '|' + prefix;
    }

    private void trimCache() {
        if (cache.size() <= MAX_CACHE_ENTRIES) {
            return;
        }
        Instant now = clock.get();
        cache.entrySet().removeIf(entry -> entry.getValue().cachedAt().plus(TTL).isBefore(now));
        if (cache.size() > MAX_CACHE_ENTRIES) {
            cache.clear();
        }
    }
}
