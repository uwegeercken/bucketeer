package io.github.uwegeercken.bucketeer.adapter.in.web;

import io.github.uwegeercken.bucketeer.domain.port.in.BucketeerUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GlobalModelAdviceTest {

    private final BucketeerUseCase useCase = mock(BucketeerUseCase.class);

    @Test
    @DisplayName("selectedServer does not touch the session for the stateless /api/v1 API")
    void apiV1DoesNotTouchSession() {
        SessionContext sessionContext = mock(SessionContext.class);
        GlobalModelAdvice advice = new GlobalModelAdvice(useCase, sessionContext, null, null);

        String result = advice.selectedServer(new MockHttpServletRequest("GET", "/api/v1/servers"));

        assertThat(result).isNull();
        verifyNoInteractions(sessionContext);
    }

    @Test
    @DisplayName("selectedServer falls back to the first server when the session has none")
    void fallsBackToFirstServer() {
        when(useCase.serverNames()).thenReturn(List.of("first", "second"));
        SessionContext sessionContext = new SessionContext();
        GlobalModelAdvice advice = new GlobalModelAdvice(useCase, sessionContext, null, null);

        String result = advice.selectedServer(new MockHttpServletRequest("GET", "/"));

        assertThat(result).isEqualTo("first");
        assertThat(sessionContext.getSelectedServer()).isEqualTo("first");
    }

    @Test
    @DisplayName("selectedServer falls back to the first server when the selected one was deleted")
    void fallsBackWhenSelectedServerWasDeleted() {
        when(useCase.serverNames()).thenReturn(List.of("first", "second"));
        SessionContext sessionContext = new SessionContext();
        sessionContext.setSelectedServer("deleted");
        GlobalModelAdvice advice = new GlobalModelAdvice(useCase, sessionContext, null, null);

        String result = advice.selectedServer(new MockHttpServletRequest("GET", "/"));

        assertThat(result).isEqualTo("first");
        assertThat(sessionContext.getSelectedServer()).isEqualTo("first");
    }

    @Test
    @DisplayName("selectedServer is cleared when the last server was deleted")
    void clearedWhenNoServersLeft() {
        when(useCase.serverNames()).thenReturn(List.of());
        SessionContext sessionContext = new SessionContext();
        sessionContext.setSelectedServer("deleted");
        GlobalModelAdvice advice = new GlobalModelAdvice(useCase, sessionContext, null, null);

        assertThat(advice.selectedServer(new MockHttpServletRequest("GET", "/"))).isNull();
    }

    @Test
    @DisplayName("selectedServer keeps a selected server that still exists")
    void keepsExistingSelection() {
        when(useCase.serverNames()).thenReturn(List.of("first", "second"));
        SessionContext sessionContext = new SessionContext();
        sessionContext.setSelectedServer("second");
        GlobalModelAdvice advice = new GlobalModelAdvice(useCase, sessionContext, null, null);

        assertThat(advice.selectedServer(new MockHttpServletRequest("GET", "/"))).isEqualTo("second");
    }
}
