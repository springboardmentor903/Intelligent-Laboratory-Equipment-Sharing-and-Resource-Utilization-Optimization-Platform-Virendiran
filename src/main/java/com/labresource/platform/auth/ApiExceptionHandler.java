package com.labresource.platform.auth;

import org.springframework.http.ResponseEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    @ExceptionHandler(BookingService.BookingException.class)
    public ResponseEntity<Map<String,String>> bookingError(BookingService.BookingException ex){return ResponseEntity.status(ex.status()).body(Map.of("error",ex.getMessage()));}
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String,String>> malformed(){return ResponseEntity.badRequest().body(Map.of("error","Request body must contain valid JSON."));}
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String,String>> unexpected(Exception ex){log.error("API_REQUEST_FAILED reason={}",ex.getMessage(),ex);return ResponseEntity.internalServerError().body(Map.of("error","The request could not be completed. Check the application log for details."));}
}
