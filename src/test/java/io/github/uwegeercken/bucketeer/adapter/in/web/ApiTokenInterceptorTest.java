package io.github.uwegeercken.bucketeer.adapter.in.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class ApiTokenInterceptorTest {

    @Test
    @DisplayName("without a configured token every request is allowed")
    void openWhenNoToken() throws Exception {
        ApiTokenInterceptor interceptor = new ApiTokenInterceptor("");
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
    }

    @Test
    @DisplayName("with a token the correct Bearer header is required")
    void tokenGatesRequests() throws Exception {
        ApiTokenInterceptor interceptor = new ApiTokenInterceptor("secret123");

        MockHttpServletRequest noHeader = new MockHttpServletRequest();
        MockHttpServletResponse rejected = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(noHeader, rejected, new Object())).isFalse();
        assertThat(rejected.getStatus()).isEqualTo(401);
        assertThat(rejected.getContentAsString()).contains("Unauthorized");

        MockHttpServletRequest wrongHeader = new MockHttpServletRequest();
        wrongHeader.addHeader("Authorization", "Bearer wrong");
        MockHttpServletResponse wrongResponse = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(wrongHeader, wrongResponse, new Object())).isFalse();
        assertThat(wrongResponse.getStatus()).isEqualTo(401);

        MockHttpServletRequest correct = new MockHttpServletRequest();
        correct.addHeader("Authorization", "Bearer secret123");
        MockHttpServletResponse ok = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(correct, ok, new Object())).isTrue();
    }

    @Test
    @DisplayName("a token is trimmed and non-Bearer schemes are rejected")
    void tokenTrimmingAndScheme() throws Exception {
        ApiTokenInterceptor interceptor = new ApiTokenInterceptor("  secret123  ");

        MockHttpServletRequest basic = new MockHttpServletRequest();
        basic.addHeader("Authorization", "Basic secret123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(basic, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }
}