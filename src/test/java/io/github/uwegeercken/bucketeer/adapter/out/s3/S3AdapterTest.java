package io.github.uwegeercken.bucketeer.adapter.out.s3;

import io.github.uwegeercken.bucketeer.domain.model.HeadObjectResult;
import io.github.uwegeercken.bucketeer.domain.model.PrefixCount;
import io.github.uwegeercken.bucketeer.domain.model.PrefixScan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.GetObjectTaggingRequest;
import software.amazon.awssdk.services.s3.model.GetObjectTaggingResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.Tag;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class S3AdapterTest {

    private S3Adapter adapter;
    private InvocationHandler handler;

    @BeforeEach
    void setUp() {
        S3ClientRegistry registry = new S3ClientRegistry() {
            @Override
            public S3Client clientFor(String serverName) {
                return proxyClient();
            }
        };
        adapter = new S3Adapter(registry);
    }

    private S3Client proxyClient() {
        return (S3Client) Proxy.newProxyInstance(
                S3Client.class.getClassLoader(),
                new Class<?>[]{S3Client.class},
                handler);
    }

    @Test
    @DisplayName("headObject maps a missing object (NoSuchKey) to not-found")
    void headObjectNotFound() {
        handler = (proxy, method, args) -> {
            if (method.getName().equals("headObject")) {
                throw NoSuchKeyException.builder().statusCode(404).build();
            }
            return method.getDefaultValue();
        };

        HeadObjectResult result = adapter.headObject("server", "bucket", "missing.txt");

        assertThat(result.exists()).isFalse();
    }

    @Test
    @DisplayName("headObject maps a generic 404 response to not-found")
    void headObjectGeneric404() {
        handler = (proxy, method, args) -> {
            if (method.getName().equals("headObject")) {
                throw S3Exception.builder().statusCode(404).message("not found").build();
            }
            return method.getDefaultValue();
        };

        HeadObjectResult result = adapter.headObject("server", "bucket", "missing.txt");

        assertThat(result.exists()).isFalse();
    }

    @Test
    @DisplayName("headObject propagates server errors instead of reporting not-found")
    void headObjectServerErrorPropagates() {
        handler = (proxy, method, args) -> {
            if (method.getName().equals("headObject")) {
                throw S3Exception.builder().statusCode(500).message("internal").build();
            }
            return method.getDefaultValue();
        };

        assertThatThrownBy(() -> adapter.headObject("server", "bucket", "key.txt"))
                .isInstanceOf(S3Exception.class);
    }

    @Test
    @DisplayName("headObject propagates network/timeout errors instead of reporting not-found")
    void headObjectNetworkErrorPropagates() {
        handler = (proxy, method, args) -> {
            if (method.getName().equals("headObject")) {
                throw new RuntimeException("connection timeout");
            }
            return method.getDefaultValue();
        };

        assertThatThrownBy(() -> adapter.headObject("server", "bucket", "key.txt"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("connection timeout");
    }

    @Test
    @DisplayName("headObject reports existence for a found object")
    void headObjectFound() {
        handler = (proxy, method, args) -> {
            if (method.getName().equals("headObject")) {
                assertThat(args[0]).isInstanceOf(HeadObjectRequest.class);
                return HeadObjectResponse.builder().contentLength(42L).build();
            }
            return method.getDefaultValue();
        };

        HeadObjectResult result = adapter.headObject("server", "bucket", "key.txt");

        assertThat(result.exists()).isTrue();
        assertThat(result.sizeBytes()).isEqualTo(42L);
    }

    @Test
    @DisplayName("getObjectTags maps the S3 tag set to a map")
    void getObjectTagsMapsTagSet() {
        handler = (proxy, method, args) -> {
            if (method.getName().equals("getObjectTagging")) {
                assertThat(args[0]).isInstanceOf(GetObjectTaggingRequest.class);
                return GetObjectTaggingResponse.builder()
                        .tagSet(
                                Tag.builder().key("env").value("prod").build(),
                                Tag.builder().key("owner").value("team-a").build())
                        .build();
            }
            return method.getDefaultValue();
        };

        Map<String, String> tags = adapter.getObjectTags("server", "bucket", "key.txt");

        assertThat(tags).containsExactly(
                Map.entry("env", "prod"),
                Map.entry("owner", "team-a"));
    }

    @Test
    @DisplayName("getObjectTags returns an empty map when no tags are set")
    void getObjectTagsEmptyWhenNoTags() {
        handler = (proxy, method, args) -> {
            if (method.getName().equals("getObjectTagging")) {
                return GetObjectTaggingResponse.builder().build();
            }
            return method.getDefaultValue();
        };

        assertThat(adapter.getObjectTags("server", "bucket", "key.txt")).isEmpty();
    }

    @Test
    @DisplayName("scanCommonPrefixes lists the next level with the slash delimiter")
    void scanCommonPrefixesMapsCommonPrefixes() {
        handler = (proxy, method, args) -> {
            if (method.getName().equals("listObjectsV2")) {
                assertThat(args[0]).isInstanceOf(ListObjectsV2Request.class);
                ListObjectsV2Request req = (ListObjectsV2Request) args[0];
                assertThat(req.bucket()).isEqualTo("bucket");
                assertThat(req.prefix()).isEqualTo("data/");
                assertThat(req.delimiter()).isEqualTo("/");
                assertThat(req.maxKeys()).isEqualTo(10);
                return ListObjectsV2Response.builder()
                        .commonPrefixes(
                                CommonPrefix.builder().prefix("data/2024/").build(),
                                CommonPrefix.builder().prefix("data/2025/").build())
                        .build();
            }
            return method.getDefaultValue();
        };

        PrefixScan scan = adapter.scanCommonPrefixes("server", "bucket", "data/", null, 10);

        assertThat(scan.prefixes()).containsExactly("data/2024/", "data/2025/");
        assertThat(scan.truncated()).isFalse();
    }

    @Test
    @DisplayName("scanCommonPrefixes passes the continuation token and reports truncation")
    void scanCommonPrefixesPassesTokenAndTruncation() {
        handler = (proxy, method, args) -> {
            if (method.getName().equals("listObjectsV2")) {
                ListObjectsV2Request req = (ListObjectsV2Request) args[0];
                assertThat(req.continuationToken()).isEqualTo("next-token");
                return ListObjectsV2Response.builder()
                        .commonPrefixes(CommonPrefix.builder().prefix("a/").build())
                        .isTruncated(true)
                        .nextContinuationToken("later-token")
                        .build();
            }
            return method.getDefaultValue();
        };

        PrefixScan scan = adapter.scanCommonPrefixes("server", "bucket", "", "next-token", 5);

        assertThat(scan.prefixes()).containsExactly("a/");
        assertThat(scan.truncated()).isTrue();
        assertThat(scan.nextContinuationToken()).isEqualTo("later-token");
    }

    @Test
    @DisplayName("scanCommonPrefixes returns an empty result when no common prefixes exist")
    void scanCommonPrefixesEmptyWhenFlatBucket() {
        handler = (proxy, method, args) -> {
            if (method.getName().equals("listObjectsV2")) {
                return ListObjectsV2Response.builder().build();
            }
            return method.getDefaultValue();
        };

        PrefixScan scan = adapter.scanCommonPrefixes("server", "bucket", "", null, 0);

        assertThat(scan.prefixes()).isEmpty();
    }

    @Test
    @DisplayName("scanCommonPrefixes caps maxKeys at the S3 page size")
    void scanCommonPrefixesCapsMaxKeys() {
        handler = (proxy, method, args) -> {
            if (method.getName().equals("listObjectsV2")) {
                ListObjectsV2Request req = (ListObjectsV2Request) args[0];
                assertThat(req.maxKeys()).isEqualTo(1000);
                return ListObjectsV2Response.builder().build();
            }
            return method.getDefaultValue();
        };

        adapter.scanCommonPrefixes("server", "bucket", "", null, 5000);
    }

    @Test
    @DisplayName("countCommonPrefixes counts the common prefixes of one page")
    void countCommonPrefixesSinglePage() {
        handler = (proxy, method, args) -> {
            if (method.getName().equals("listObjectsV2")) {
                ListObjectsV2Request req = (ListObjectsV2Request) args[0];
                assertThat(req.bucket()).isEqualTo("bucket");
                assertThat(req.prefix()).isEqualTo("data/");
                assertThat(req.delimiter()).isEqualTo("/");
                assertThat(req.maxKeys()).isEqualTo(1000);
                return ListObjectsV2Response.builder()
                        .commonPrefixes(
                                CommonPrefix.builder().prefix("data/2024/").build(),
                                CommonPrefix.builder().prefix("data/2025/").build())
                        .build();
            }
            return method.getDefaultValue();
        };

        PrefixCount count = adapter.countCommonPrefixes("server", "bucket", "data/");

        assertThat(count.count()).isEqualTo(2);
        assertThat(count.capped()).isFalse();
    }

    @Test
    @DisplayName("countCommonPrefixes paginates with the continuation token until complete")
    void countCommonPrefixesPaginates() {
        int[] calls = {0};
        handler = (proxy, method, args) -> {
            if (method.getName().equals("listObjectsV2")) {
                ListObjectsV2Request req = (ListObjectsV2Request) args[0];
                if (calls[0] == 1) {
                    assertThat(req.continuationToken()).isEqualTo("t2");
                }
                calls[0]++;
                return calls[0] == 1
                        ? ListObjectsV2Response.builder()
                        .commonPrefixes(CommonPrefix.builder().prefix("a/").build())
                        .isTruncated(true)
                        .nextContinuationToken("t2")
                        .build()
                        : ListObjectsV2Response.builder()
                        .commonPrefixes(
                                CommonPrefix.builder().prefix("b/").build(),
                                CommonPrefix.builder().prefix("c/").build())
                        .build();
            }
            return method.getDefaultValue();
        };

        PrefixCount count = adapter.countCommonPrefixes("server", "bucket", "data/");

        assertThat(calls[0]).isEqualTo(2);
        assertThat(count.count()).isEqualTo(3);
        assertThat(count.capped()).isFalse();
    }

    @Test
    @DisplayName("countCommonPrefixes is capped at the internal limit and reports it")
    void countCommonPrefixesCapped() {
        List<CommonPrefix> many = IntStream.range(0, 1000)
                .mapToObj(i -> CommonPrefix.builder().prefix("p" + i + "/").build())
                .toList();
        handler = (proxy, method, args) -> {
            if (method.getName().equals("listObjectsV2")) {
                return ListObjectsV2Response.builder()
                        .commonPrefixes(many)
                        .isTruncated(true)
                        .nextContinuationToken("t2")
                        .build();
            }
            return method.getDefaultValue();
        };

        PrefixCount count = adapter.countCommonPrefixes("server", "bucket", "data/");

        assertThat(count.count()).isEqualTo(1000);
        assertThat(count.capped()).isTrue();
    }

    @Test
    @DisplayName("countCommonPrefixes returns zero when no common prefixes exist")
    void countCommonPrefixesZeroOnFlatBucket() {
        handler = (proxy, method, args) -> {
            if (method.getName().equals("listObjectsV2")) {
                return ListObjectsV2Response.builder().build();
            }
            return method.getDefaultValue();
        };

        PrefixCount count = adapter.countCommonPrefixes("server", "bucket", "");

        assertThat(count.count()).isEqualTo(0);
        assertThat(count.capped()).isFalse();
    }
}
