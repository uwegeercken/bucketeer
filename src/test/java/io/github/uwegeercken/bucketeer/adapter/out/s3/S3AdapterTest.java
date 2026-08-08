package io.github.uwegeercken.bucketeer.adapter.out.s3;

import io.github.uwegeercken.bucketeer.domain.model.HeadObjectResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;

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
}
