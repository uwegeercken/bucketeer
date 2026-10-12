package io.github.uwegeercken.bucketeer.adapter.in.web;

import io.github.uwegeercken.bucketeer.adapter.out.s3.S3ClientRegistry;
import io.github.uwegeercken.bucketeer.domain.port.in.BucketeerUseCase;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.util.Map;

/**
 * Catches exceptions from page-rendering controllers and re-populates the model
 * so that the index page renders correctly even on error.
 * Form field values are preserved by reading them from the original request.
 *
 * <p>Requests under {@code /api/**} are JSON endpoints: they get a JSON error body with a
 * matching status instead of the index page. Returning the HTML page (with HTTP 200) would
 * break the browser's {@code response.json()} and hide the real error.</p>
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final BucketeerUseCase bucketeerUseCase;
    private final S3ClientRegistry s3ClientRegistry;
    private final SessionContext sessionContext;

    public GlobalExceptionHandler(BucketeerUseCase bucketeerUseCase,
                                  S3ClientRegistry s3ClientRegistry,
                                  SessionContext sessionContext) {
        this.bucketeerUseCase  = bucketeerUseCase;
        this.s3ClientRegistry  = s3ClientRegistry;
        this.sessionContext    = sessionContext;
    }

    /** Let Spring handle missing static resources (e.g. favicon.ico) normally. */
    @ExceptionHandler(NoResourceFoundException.class)
    public void handleNoResource(NoResourceFoundException ex) throws NoResourceFoundException {
        throw ex;
    }

    @ExceptionHandler(S3Exception.class)
    public Object handleS3Exception(S3Exception ex, HttpServletRequest request) {
        log.error("S3 error: {}", ex.awsErrorDetails().errorMessage());
        return errorResponse("S3 Fehler: " + ex.awsErrorDetails().errorMessage()
                + " (Code: " + ex.awsErrorDetails().errorCode() + ")",
                HttpStatus.INTERNAL_SERVER_ERROR, request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public Object handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest request) {
        log.error("Invalid argument: {}", ex.getMessage());
        return errorResponse(ex.getMessage(), HttpStatus.BAD_REQUEST, request);
    }

    @ExceptionHandler(Exception.class)
    public Object handleGeneral(Exception ex, HttpServletRequest request) {
        log.error("Unexpected error: {}", ex.getMessage());
        return errorResponse("Unerwarteter Fehler: " + ex.getMessage(),
                HttpStatus.INTERNAL_SERVER_ERROR, request);
    }

    /**
     * Returns a JSON error for {@code /api/**} requests and the index page otherwise.
     * The message may be {@code null}; both branches guard against that.
     */
    private Object errorResponse(String message, HttpStatus status, HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri != null && uri.startsWith("/api/")) {
            return ResponseEntity.status(status)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("error", message != null ? message : ""));
        }
        return errorView(message, request);
    }

    private ModelAndView errorView(String message, HttpServletRequest request) {
        ModelAndView mav = new ModelAndView("index");
        mav.addObject("errorMessage", message);
        mav.addObject("availableFunctions", bucketeerUseCase.availableFunctions());
        mav.addObject("bucket", request.getParameter("bucket"));
        mav.addObject("prefix", request.getParameter("prefix"));
        mav.addObject("key",    request.getParameter("key"));

        // re-populate server list directly from registry (safe even after exceptions)
        java.util.List<String> serverNames = s3ClientRegistry.serverNames();
        mav.addObject("serverNames", serverNames);
        mav.addObject("selectedServer", sessionContext.getSelectedServer());

        return mav;
    }
}