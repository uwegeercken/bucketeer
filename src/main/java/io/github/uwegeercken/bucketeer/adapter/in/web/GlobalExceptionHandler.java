package io.github.uwegeercken.bucketeer.adapter.in.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.ModelAndView;
import software.amazon.awssdk.services.s3.model.S3Exception;

@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(S3Exception.class)
    public ModelAndView handleS3Exception(S3Exception ex) {
        log.error("S3 error: {}", ex.awsErrorDetails().errorMessage(), ex);
        ModelAndView mav = new ModelAndView("index");
        mav.addObject("errorMessage", "S3 Fehler: " + ex.awsErrorDetails().errorMessage()
                + " (Code: " + ex.awsErrorDetails().errorCode() + ")");
        return mav;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ModelAndView handleIllegalArgument(IllegalArgumentException ex) {
        log.error("Invalid argument: {}", ex.getMessage(), ex);
        ModelAndView mav = new ModelAndView("index");
        mav.addObject("errorMessage", ex.getMessage());
        return mav;
    }

    @ExceptionHandler(Exception.class)
    public ModelAndView handleGeneral(Exception ex) {
        log.error("Unexpected error: {}", ex.getMessage(), ex);
        ModelAndView mav = new ModelAndView("index");
        mav.addObject("errorMessage", "Unerwarteter Fehler: " + ex.getMessage());
        return mav;
    }
}
