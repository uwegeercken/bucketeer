package io.github.uwegeercken.bucketeer.adapter.in.web;

import io.github.uwegeercken.bucketeer.domain.port.in.BucketeerUseCase;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SelectedServerInterceptorTest {

    private SessionContext sessionContext;
    private SelectedServerInterceptor interceptor;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        BucketeerUseCase useCase = mock(BucketeerUseCase.class);
        when(useCase.serverNames()).thenReturn(List.of("minio-a", "minio b"));
        sessionContext = new SessionContext();
        interceptor    = new SelectedServerInterceptor(sessionContext, useCase);
        request        = new MockHttpServletRequest();
        response       = new MockHttpServletResponse();
    }

    @Test
    @DisplayName("a fresh session restores the server from the cookie without rewriting it")
    void restoresFromCookie() {
        request.setCookies(new Cookie(SelectedServerInterceptor.COOKIE_NAME, "minio+b"));

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();

        assertThat(sessionContext.getSelectedServer()).isEqualTo("minio b");
        assertThat(response.getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    }

    @Test
    @DisplayName("a cookie naming an unknown server is ignored")
    void ignoresUnknownServer() {
        request.setCookies(new Cookie(SelectedServerInterceptor.COOKIE_NAME, "deleted-server"));

        interceptor.preHandle(request, response, new Object());

        assertThat(sessionContext.getSelectedServer()).isNull();
        assertThat(response.getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    }

    @Test
    @DisplayName("a session selection that differs from the cookie updates the cookie")
    void syncsCookieWithSession() {
        sessionContext.setSelectedServer("minio b");
        request.setCookies(new Cookie(SelectedServerInterceptor.COOKIE_NAME, "minio-a"));

        interceptor.preHandle(request, response, new Object());

        assertThat(sessionContext.getSelectedServer()).isEqualTo("minio b");
        assertThat(response.getHeader(HttpHeaders.SET_COOKIE))
                .startsWith(SelectedServerInterceptor.COOKIE_NAME + "=minio+b")
                .contains("Path=/", "HttpOnly", "SameSite=Lax", "Max-Age=");
    }

    @Test
    @DisplayName("without cookie and selection nothing is written")
    void noCookieNoSelection() {
        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();

        assertThat(sessionContext.getSelectedServer()).isNull();
        assertThat(response.getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    }
}
