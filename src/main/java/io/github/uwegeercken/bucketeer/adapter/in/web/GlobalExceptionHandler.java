package io.github.uwegeercken.bucketeer.adapter.in.web;

import io.github.uwegeercken.bucketeer.domain.port.in.BucketeerUseCase;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.ModelAndView;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.util.List;

/**
 * Catches exceptions from page-rendering controllers and re-populates the model
 * so that the index page renders correctly even on error.
 * Form field values are preserved by reading them from the original request.
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final BucketeerUseCase bucketeerUseCase;
    private final SessionContext sessionContext;

    public GlobalExceptionHandler(BucketeerUseCase bucketeerUseCase, SessionContext sessionContext) {
        this.bucketeerUseCase = bucketeerUseCase;
        this.sessionContext   = sessionContext;
    }

    @ExceptionHandler(S3Exception.class)
    public ModelAndView handleS3Exception(S3Exception ex, HttpServletRequest request) {
        log.error("S3 error: {}", ex.awsErrorDetails().errorMessage(), ex);
        return errorView("S3 Fehler: " + ex.awsErrorDetails().errorMessage()
                + " (Code: " + ex.awsErrorDetails().errorCode() + ")", request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ModelAndView handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest request) {
        log.error("Invalid argument: {}", ex.getMessage(), ex);
        return errorView(ex.getMessage(), request);
    }

    @ExceptionHandler(Exception.class)
    public ModelAndView handleGeneral(Exception ex, HttpServletRequest request) {
        log.error("Unexpected error: {}", ex.getMessage(), ex);
        return errorView("Unerwarteter Fehler: " + ex.getMessage(), request);
    }

    private ModelAndView errorView(String message, HttpServletRequest request) {
        ModelAndView mav = new ModelAndView("index");
        mav.addObject("errorMessage", message);
        mav.addObject("availableFunctions", bucketeerUseCase.availableFunctions());
        mav.addObject("bucket", request.getParameter("bucket"));
        mav.addObject("prefix", request.getParameter("prefix"));
        mav.addObject("key",    request.getParameter("key"));
        return mav;
    }
}
