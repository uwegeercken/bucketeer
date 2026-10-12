package io.github.uwegeercken.bucketeer.adapter.in.web;

import io.github.uwegeercken.bucketeer.adapter.out.s3.S3ClientRegistry;
import io.github.uwegeercken.bucketeer.domain.port.in.BucketeerUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.ModelAndView;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalExceptionHandlerTest {

    private final BucketeerUseCase useCase = mock(BucketeerUseCase.class);
    private final S3ClientRegistry registry = mock(S3ClientRegistry.class);
    private final SessionContext sessionContext = mock(SessionContext.class);
    private final GlobalExceptionHandler handler =
            new GlobalExceptionHandler(useCase, registry, sessionContext);

    @Test
    @DisplayName("an /api/** error is returned as JSON with a matching status instead of the index page")
    void apiErrorIsJson() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/keycheck/check");

        Object response = handler.handleGeneral(new IllegalStateException("boom"), request);

        assertThat(response).isInstanceOf(ResponseEntity.class);
        ResponseEntity<?> entity = (ResponseEntity<?>) response;
        assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(entity.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(entity.getBody()).isEqualTo(Map.of("error", "Unerwarteter Fehler: boom"));
    }

    @Test
    @DisplayName("an invalid argument under /api/** surfaces as HTTP 400 with a JSON body")
    void apiIllegalArgumentIsBadRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/snapshots/s1/export");

        Object response = handler.handleIllegalArgument(new IllegalArgumentException("bad input"), request);

        ResponseEntity<?> entity = (ResponseEntity<?>) response;
        assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(entity.getBody()).isEqualTo(Map.of("error", "bad input"));
    }

    @Test
    @DisplayName("a page-controller error still renders the index view")
    void pageErrorRendersIndex() {
        when(useCase.availableFunctions()).thenReturn(List.of());
        when(registry.serverNames()).thenReturn(List.of());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");

        Object response = handler.handleGeneral(new IllegalStateException("boom"), request);

        assertThat(response).isInstanceOf(ModelAndView.class);
        assertThat(((ModelAndView) response).getViewName()).isEqualTo("index");
    }
}
