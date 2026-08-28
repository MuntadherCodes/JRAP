package dev.hmcodes.jrap.api.error;

import dev.hmcodes.jrap.common.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

/** Renders all errors as RFC 9457 problem+json (SRS §3.2.2). */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ProblemDetail handleApiException(ApiException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.valueOf(e.status()), e.getMessage());
        problem.setType(URI.create("https://jrap.dev/errors/" + e.code()));
        problem.setTitle(e.code());
        return problem;
    }

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(org.springframework.security.access.AccessDeniedException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.FORBIDDEN, "You do not have permission to perform this action");
        problem.setType(URI.create("https://jrap.dev/errors/forbidden"));
        problem.setTitle("forbidden");
        return problem;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Request validation failed");
        problem.setType(URI.create("https://jrap.dev/errors/validation"));
        problem.setTitle("validation");
        problem.setProperty("errors", e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .toList());
        return problem;
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadableBody(org.springframework.http.converter.HttpMessageNotReadableException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Request body is malformed or has an invalid value");
        problem.setType(URI.create("https://jrap.dev/errors/malformed-body"));
        problem.setTitle("malformed-body");
        return problem;
    }

    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleTypeMismatch(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Request parameter '" + e.getName() + "' has an invalid value");
        problem.setType(URI.create("https://jrap.dev/errors/invalid-parameter"));
        problem.setTitle("invalid-parameter");
        return problem;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception e) {
        // Framework exceptions (404 for unknown routes, 405, 406, ...) already carry the
        // right problem body — pass them through instead of masking them as 500s.
        if (e instanceof org.springframework.web.ErrorResponse errorResponse) {
            return errorResponse.getBody();
        }
        log.error("Unhandled exception", e);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
        problem.setType(URI.create("https://jrap.dev/errors/internal"));
        problem.setTitle("internal");
        return problem;
    }
}
