package io.github.uwegeercken.bucketeer.adapter.in.web;

import io.github.uwegeercken.bucketeer.domain.port.in.BucketeerUseCase;
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
    public ModelAndView handleS3Exception(S3Exception ex) {
        log.error("S3 error: {}", ex.awsErrorDetails().errorMessage(), ex);
        return errorView("S3 Fehler: " + ex.awsErrorDetails().errorMessage()
                + " (Code: " + ex.awsErrorDetails().errorCode() + ")");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ModelAndView handleIllegalArgument(IllegalArgumentException ex) {
        log.error("Invalid argument: {}", ex.getMessage(), ex);
        return errorView(ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ModelAndView handleGeneral(Exception ex) {
        log.error("Unexpected error: {}", ex.getMessage(), ex);
        return errorView("Unerwarteter Fehler: " + ex.getMessage());
    }

    private ModelAndView errorView(String message) {
        ModelAndView mav = new ModelAndView("index");
        mav.addObject("errorMessage", message);

        // re-populate common model attributes so the page renders correctly
        List<String> serverNames = bucketeerUseCase.serverNames();
        mav.addObject("serverNames", serverNames);
        mav.addObject("selectedServer", sessionContext.getSelectedServer());
        mav.addObject("availableFunctions", bucketeerUseCase.availableFunctions());
        return mav;
    }
}
