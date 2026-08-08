package io.github.uwegeercken.bucketeer.application;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.Tag;
import software.amazon.awssdk.services.s3.model.Tagging;

import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Deterministic test-data seeder for any S3-compatible server (MinIO, StorageGRID, ...).
 * Invoked via {@code java -jar bucketeer.jar --seed [options]}. No Spring context is started.
 */
public final class SeedRunner {

    private static final long RANDOM_SEED = 20260802L;

    private SeedRunner() {}

    /** Tags applied to every seeded object. */
    static Tagging objectTags() {
        return Tagging.builder()
                .tagSet(
                        Tag.builder().key("type").value("testdata").build(),
                        Tag.builder().key("loader").value("seedrunner").build())
                .build();
    }

    public static int run(String[] args) {
        Options opts = Options.parse(args);
        if (opts == null) {
            printUsage();
            return 1;
        }
        System.out.println("Seed parameters:");
        System.out.println("  endpoint : " + opts.endpoint);
        System.out.println("  bucket   : " + opts.bucket);
        System.out.println("  count    : " + opts.count);
        System.out.println("  prefixes : " + opts.prefixes);
        System.out.println("  size     : " + opts.sizeMin + ".." + opts.sizeMax + " bytes");
        System.out.println("  parallel : " + opts.parallel);
        System.out.println("  tags     : type=testdata, loader=seedrunner");
        if (opts.noVerifySsl) System.out.println("  ssl      : verify disabled");
        if (opts.empty) System.out.println("  empty    : true");

        if (opts.dryRun) {
            System.out.println();
            System.out.println("DRY RUN - nothing was written to S3.");
            if (opts.count > 0) {
                System.out.println("Structure example:");
                System.out.println("  " + opts.bucket + "/" + keyFor(0, opts.prefixes));
                System.out.println("  " + opts.bucket + "/" + keyFor(1, opts.prefixes));
                System.out.println("  ...");
                System.out.println("  " + opts.bucket + "/" + keyFor(opts.count - 1, opts.prefixes));
            }
            return 0;
        }

        try {
            return seed(opts);
        } catch (Exception e) {
            System.err.println("Seed failed: " + e.getMessage());
            return 1;
        }
    }

    /** Key layout: events/shard-XX/event-NNNNNN.json, round-robin over the shard prefixes. */
    static String keyFor(int index, int prefixes) {
        return String.format("events/shard-%02d/event-%06d.json", index % prefixes, index);
    }

    static String formatBytes(long bytes) {
        if (bytes >= 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
        if (bytes >= 1024L * 1024) return String.format(Locale.ROOT, "%.2f MB", bytes / (1024.0 * 1024));
        if (bytes >= 1024) return String.format(Locale.ROOT, "%.2f KB", bytes / 1024.0);
        return bytes + " B";
    }

    private static int seed(Options opts) {
        try (S3Client s3 = buildClient(opts)) {
            createBucket(s3, opts.bucket);
            if (opts.empty) {
                emptyBucket(s3, opts.bucket);
            }

            long[] sizes = new long[opts.count];
            Random rng = new Random(RANDOM_SEED);
            for (int i = 0; i < opts.count; i++) {
                sizes[i] = opts.sizeMin + (int) (rng.nextDouble() * (opts.sizeMax - opts.sizeMin + 1));
            }

            System.out.println();
            System.out.println("Uploading " + opts.count + " objects...");
            AtomicInteger done = new AtomicInteger();
            AtomicInteger failed = new AtomicInteger();
            ExecutorService pool = Executors.newFixedThreadPool(opts.parallel);
            for (int i = 0; i < opts.count; i++) {
                final String key = keyFor(i, opts.prefixes);
                final long size = sizes[i];
                pool.submit(() -> {
                    try {
                        s3.putObject(PutObjectRequest.builder()
                                        .bucket(opts.bucket)
                                        .key(key)
                                        .tagging(objectTags())
                                        .build(),
                                RequestBody.fromBytes(content(key, size)));
                    } catch (Exception e) {
                        failed.incrementAndGet();
                        System.err.println("Put failed for " + key + ": " + e.getMessage());
                    }
                    int d = done.incrementAndGet();
                    if (d % 500 == 0 || d == opts.count) {
                        System.out.println("Uploaded " + d + " / " + opts.count);
                    }
                });
            }
            pool.shutdown();
            try {
                if (!pool.awaitTermination(1, TimeUnit.HOURS)) {
                    System.err.println("Seed timed out.");
                    return 1;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return 1;
            }

            long[] stats = countObjects(s3, opts.bucket);
            System.out.println();
            System.out.println("Done. Bucket '" + opts.bucket + "' contains " + stats[0]
                    + " objects, " + formatBytes(stats[1]) + " total.");
            if (failed.get() > 0) {
                System.out.println("WARNING: " + failed.get() + " uploads failed.");
                return 1;
            }
            return 0;
        }
    }

    private static byte[] content(String key, long size) {
        byte[] body = new byte[(int) size];
        byte[] header = ("bucketeer-seed-object " + key + "\n").getBytes(StandardCharsets.UTF_8);
        int n = Math.min(header.length, body.length);
        System.arraycopy(header, 0, body, 0, n);
        for (int i = n; i < body.length; i++) {
            body[i] = (byte) ('0' + (i % 10));
        }
        return body;
    }

    private static S3Client buildClient(Options opts) {
        S3ClientBuilder builder = S3Client.builder()
                .endpointOverride(URI.create(opts.endpoint))
                .region(Region.of(opts.region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(opts.accessKey, opts.secretKey)))
                .forcePathStyle(true);
        if (opts.noVerifySsl) {
            builder.httpClientBuilder(UrlConnectionHttpClient.builder()
                    .tlsTrustManagersProvider(() -> new TrustManager[]{new TrustAllTrustManager()}));
        }
        return builder.build();
    }

    private static void createBucket(S3Client s3, String bucket) {
        try {
            s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
            System.out.println("Created bucket " + bucket);
        } catch (S3Exception e) {
            String code = e.awsErrorDetails() != null ? e.awsErrorDetails().errorCode() : "";
            if (e.statusCode() == 409 || "BucketAlreadyOwnedByYou".equals(code) || "BucketAlreadyExists".equals(code)) {
                System.out.println("Bucket " + bucket + " already exists");
            } else {
                throw e;
            }
        }
    }

    private static void emptyBucket(S3Client s3, String bucket) {
        long deleted = 0;
        String token = null;
        boolean truncated;
        do {
            ListObjectsV2Request.Builder req = ListObjectsV2Request.builder().bucket(bucket);
            if (token != null) {
                req.continuationToken(token);
            }
            ListObjectsV2Response resp = s3.listObjectsV2(req.build());
            List<ObjectIdentifier> ids = resp.contents().stream()
                    .map(o -> ObjectIdentifier.builder().key(o.key()).build())
                    .toList();
            for (int i = 0; i < ids.size(); i += 1000) {
                List<ObjectIdentifier> chunk = ids.subList(i, Math.min(i + 1000, ids.size()));
                DeleteObjectsResponse del = s3.deleteObjects(DeleteObjectsRequest.builder()
                        .bucket(bucket)
                        .delete(Delete.builder().objects(chunk).build())
                        .build());
                deleted += chunk.size() - del.errors().size();
            }
            token = resp.nextContinuationToken();
            truncated = resp.isTruncated();
        } while (truncated && token != null);
        System.out.println("Emptied bucket " + bucket + ": removed " + deleted + " objects");
    }

    private static long[] countObjects(S3Client s3, String bucket) {
        long count = 0;
        long bytes = 0;
        String token = null;
        boolean truncated;
        do {
            ListObjectsV2Request.Builder req = ListObjectsV2Request.builder().bucket(bucket);
            if (token != null) {
                req.continuationToken(token);
            }
            ListObjectsV2Response resp = s3.listObjectsV2(req.build());
            for (S3Object obj : resp.contents()) {
                count++;
                bytes += obj.size();
            }
            token = resp.nextContinuationToken();
            truncated = resp.isTruncated();
        } while (truncated && token != null);
        return new long[]{count, bytes};
    }

    private static void printUsage() {
        System.err.println("Usage: java -jar bucketeer.jar --seed [options]");
        System.err.println("  --endpoint=URL      S3-compatible endpoint (default http://localhost:9000)");
        System.err.println("  --access-key=KEY    access key (default admin)");
        System.err.println("  --secret-key=KEY    secret key (default admin123)");
        System.err.println("  --region=REGION     AWS region (default us-east-1)");
        System.err.println("  --no-verify-ssl     trust all certificates (e.g. StorageGRID without a valid cert)");
        System.err.println("  --bucket=NAME       target bucket (default testdata)");
        System.err.println("  --count=N           number of objects (default 3000)");
        System.err.println("  --prefixes=N        prefix fan-out / shard count (default 20)");
        System.err.println("  --size-min=BYTES    minimum object size (default 1024)");
        System.err.println("  --size-max=BYTES    maximum object size (default 10240)");
        System.err.println("  --parallel=N        parallel upload threads (default 10)");
        System.err.println("  --empty             delete all objects in the bucket first");
        System.err.println("  --dry-run           print the plan without touching S3");
    }

    static final class Options {
        String endpoint = "http://localhost:9000";
        String accessKey = "admin";
        String secretKey = "admin123";
        String region = "us-east-1";
        boolean noVerifySsl;
        String bucket = "testdata";
        int count = 3000;
        int prefixes = 20;
        int sizeMin = 1024;
        int sizeMax = 10240;
        int parallel = 10;
        boolean empty;
        boolean dryRun;

        static Options parse(String[] args) {
            Options o = new Options();
            for (String arg : args) {
                if (!arg.startsWith("--")) {
                    return null;
                }
                String body = arg.substring(2);
                String key = body;
                String value = null;
                int eq = body.indexOf('=');
                if (eq >= 0) {
                    key = body.substring(0, eq);
                    value = body.substring(eq + 1);
                }
                if (value == null) {
                    switch (key) {
                        case "no-verify-ssl" -> o.noVerifySsl = true;
                        case "empty" -> o.empty = true;
                        case "dry-run" -> o.dryRun = true;
                        default -> {
                            return null;
                        }
                    }
                } else {
                    switch (key) {
                        case "endpoint" -> o.endpoint = value;
                        case "access-key" -> o.accessKey = value;
                        case "secret-key" -> o.secretKey = value;
                        case "region" -> o.region = value;
                        case "bucket" -> o.bucket = value;
                        case "count" -> o.count = parseInt(value);
                        case "prefixes" -> o.prefixes = parseInt(value);
                        case "size-min" -> o.sizeMin = parseInt(value);
                        case "size-max" -> o.sizeMax = parseInt(value);
                        case "parallel" -> o.parallel = parseInt(value);
                        default -> {
                            return null;
                        }
                    }
                }
            }
            if (o.count < 0 || o.prefixes < 1 || o.parallel < 1
                    || o.sizeMin < 0 || o.sizeMax < o.sizeMin) {
                return null;
            }
            return o;
        }

        private static int parseInt(String value) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                return -1;
            }
        }
    }

    /** Trust manager that accepts all certificates. Only used when --no-verify-ssl is set. */
    private static class TrustAllTrustManager implements X509TrustManager {
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }
}
