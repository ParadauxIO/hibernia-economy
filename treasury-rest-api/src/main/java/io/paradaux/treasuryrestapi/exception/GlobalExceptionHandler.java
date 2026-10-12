package io.paradaux.treasuryrestapi.exception;

import io.paradaux.treasuryrestapi.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Comparator;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApiException(ApiException ex) {
        if (ex.getStatus().is5xxServerError()) {
            log.error("API error {}: [{}] {}", ex.getStatus().value(), ex.getErrorCode(), ex.getMessage());
        } else {
            log.warn("API error {}: [{}] {}", ex.getStatus().value(), ex.getErrorCode(), ex.getMessage());
        }
        return ResponseEntity
                .status(ex.getStatus())
                .body(new ErrorResponse(ex.getErrorCode(), ex.getMessage()));
    }

    /**
     * Error codes for fields whose service-layer validation already answers with
     * something more specific than {@code INVALID_BODY}. Bean validation runs before
     * the service, so without this a blank {@code amount} would stop being
     * {@code INVALID_AMOUNT} (and a blank webhook URL {@code INVALID_WEBHOOK_URL}) the
     * moment a constraint annotation is added to the DTO — a silent contract break.
     */
    private static final Map<String, String> FIELD_ERROR_CODES = Map.of(
            "amount", "INVALID_AMOUNT",
            "url", "INVALID_WEBHOOK_URL",
            "targetUrl", "INVALID_WEBHOOK_URL");

    /**
     * Bean-validation failure on a {@code @Valid @RequestBody} DTO. Surfaces one
     * field error in the stable {@link ErrorResponse} envelope rather than Spring's
     * default validation payload, under the same code the service layer uses for
     * that field ({@code INVALID_BODY} unless listed in {@link #FIELD_ERROR_CODES}).
     *
     * <p>When several fields fail, plain body errors are reported ahead of the
     * specifically-coded ones — the order the services check them in — and ties
     * break on field name, so the response never depends on validator iteration order.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        FieldError first = ex.getBindingResult().getFieldErrors().stream()
                .min(Comparator.comparing((FieldError e) -> FIELD_ERROR_CODES.containsKey(e.getField()))
                        .thenComparing(FieldError::getField))
                .orElse(null);
        String code = first != null
                ? FIELD_ERROR_CODES.getOrDefault(first.getField(), "INVALID_BODY")
                : "INVALID_BODY";
        String message = first != null
                ? "Field '" + first.getField() + "' " + first.getDefaultMessage() + "."
                : "Request body failed validation.";
        log.warn("Request validation failed: [{}] {}", code, message);
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(code, message));
    }

    /** Malformed or missing request body. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(HttpMessageNotReadableException ex) {
        log.warn("Malformed request body: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("INVALID_BODY", "Request body is missing or malformed JSON."));
    }

    /** Path variable or query parameter type mismatch (e.g. non-integer accountId). */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        log.warn("Type mismatch on parameter '{}': value='{}' expectedType={}", ex.getName(), ex.getValue(),
                ex.getRequiredType() != null ? ex.getRequiredType().getSimpleName() : "unknown");
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("INVALID_PARAM", "Path or query parameter '" + ex.getName() + "' is invalid."));
    }

    /** Bot scanners probing for static resources (swagger-ui, favicon, etc.). */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFound(NoResourceFoundException ex) {
        log.debug("Static resource not found: {}", ex.getResourcePath());
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("NOT_FOUND", "The requested resource does not exist."));
    }

    /**
     * Wrong HTTP method for an existing route (e.g. POST to a GET-only endpoint).
     * Without this handler, {@link #handleUnexpected} catches the Spring 405
     * exception and turns it into a misleading 500 "internal server error" —
     * which is what surfaced to clients hitting {@code GET /accounts/by-player}
     * with a POST.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex,
                                                                  HttpServletRequest request) {
        String allowed = ex.getSupportedHttpMethods() != null
                ? ex.getSupportedHttpMethods().toString() : "[]";
        log.warn("405 {} {} — supported={}", ex.getMethod(), request.getRequestURI(), allowed);
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED);
        if (ex.getSupportedHttpMethods() != null && !ex.getSupportedHttpMethods().isEmpty()) {
            builder = builder.header("Allow", String.join(", ",
                    ex.getSupportedHttpMethods().stream().map(Object::toString).toList()));
        }
        return builder.body(new ErrorResponse("METHOD_NOT_ALLOWED",
                "Method " + ex.getMethod() + " is not supported on this endpoint. Allowed: " + allowed + "."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("INTERNAL_ERROR", "An unexpected error occurred."));
    }
}
