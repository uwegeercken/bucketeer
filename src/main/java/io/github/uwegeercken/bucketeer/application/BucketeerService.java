package io.github.uwegeercken.bucketeer.application;

import io.github.uwegeercken.bucketeer.domain.model.ObjectListing;
import io.github.uwegeercken.bucketeer.domain.model.PrefixCount;
import io.github.uwegeercken.bucketeer.domain.model.PrefixScan;
import io.github.uwegeercken.bucketeer.domain.port.in.BucketeerUseCase;
import io.github.uwegeercken.bucketeer.domain.port.out.S3StoragePort;
import io.github.uwegeercken.bucketeer.domain.template.PrefixTemplateEngine;
import io.github.uwegeercken.bucketeer.infrastructure.config.S3Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Service
public class BucketeerService implements BucketeerUseCase {

    private static final Logger log = LoggerFactory.getLogger(BucketeerService.class);

    public static final int DEFAULT_SCAN_MAX_PREFIXES = 200;
    public static final int DEFAULT_SCAN_MAX_DEPTH = 10;

    private final S3StoragePort s3StoragePort;
    private final PrefixTemplateEngine templateEngine;
    private final S3Properties s3Properties;

    public BucketeerService(S3StoragePort s3StoragePort, PrefixTemplateEngine templateEngine, S3Properties s3Properties) {
        this.s3StoragePort = s3StoragePort;
        this.templateEngine = templateEngine;
        this.s3Properties = s3Properties;
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
        log.info("Scan {}/{} '{}': found {} prefix(es){}", serverName, bucket,
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
        String token = null;
        long count = 0;
        do {
            long remaining = maxObjects > 0 ? Math.max(0, maxObjects - count) : 0;
            ObjectListing page = s3StoragePort.listObjects(serverName, bucket, resolvedPrefix, token,
                    maxObjects > 0 ? Math.min(remaining, 1000) : 0);
            pageCallback.accept(page);
            count += page.objects().size();
            token = page.truncated() ? page.nextContinuationToken() : null;
            if (maxObjects > 0 && count >= maxObjects) {
                return true;
            }
        } while (token != null);
        return false;
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