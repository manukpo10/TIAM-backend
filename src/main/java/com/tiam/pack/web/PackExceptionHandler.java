package com.tiam.pack.web;

import com.tiam.common.exception.BadRequestException;
import com.tiam.common.exception.ResourceNotFoundException;
import com.tiam.common.web.ApiError;
import com.tiam.pack.service.PackDownloadLimitException;
import com.tiam.pack.service.PackRateLimitedException;
import com.tiam.pack.service.PackUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Error mapping for the JSON pack endpoints only (the checkout form has its own redirect-style handlers).
 *
 * <p>It exists for two reasons. First, 429 and 503 get their proper status instead of a generic 500.
 * Second, the order token is a credential: the app-wide handler echoes the request path into every error
 * body, which for these endpoints would put the token into a response that proxies and tools log. Here the
 * path is returned with the token replaced by {@code {token}}.
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.tiam.pack.web")
public class PackExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(ResourceNotFoundException ex, HttpServletRequest request) {
        // One generic message for every kind of "no": unknown, expired, revoked, never paid.
        return error(HttpStatus.NOT_FOUND, "Not Found", "Not found", request, null);
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ApiError> handleBadRequest(BadRequestException ex, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "Bad Request", ex.getMessage(), request, null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<String> details = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .toList();
        return error(HttpStatus.BAD_REQUEST, "Validation Failed", "Invalid request body", request, details);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableBody(HttpMessageNotReadableException ex, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "Bad Request", "Invalid request body", request, null);
    }

    @ExceptionHandler(PackRateLimitedException.class)
    public ResponseEntity<ApiError> handleRateLimited(PackRateLimitedException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, "60")
                .cacheControl(CacheControl.noStore())
                .body(ApiError.of(429, "Too Many Requests", "Demasiados intentos. Esperá un momento y probá de nuevo.",
                        maskedPath(request)));
    }

    @ExceptionHandler(PackDownloadLimitException.class)
    public ResponseEntity<ApiError> handleDownloadLimit(PackDownloadLimitException ex, HttpServletRequest request) {
        boolean daily = ex.cap() == PackDownloadLimitException.Cap.DAILY;
        String message = daily
                ? "Llegaste al límite de descargas de hoy. Probá de nuevo mañana."
                : "Llegaste al límite total de descargas de este pedido. Escribinos y lo revisamos.";
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, daily ? "3600" : "86400")
                .cacheControl(CacheControl.noStore())
                .body(ApiError.of(429, "Download Limit", message, maskedPath(request)));
    }

    @ExceptionHandler(PackUnavailableException.class)
    public ResponseEntity<ApiError> handleUnavailable(PackUnavailableException ex, HttpServletRequest request) {
        log.warn("Pack endpoint unavailable on {}: {}", maskedPath(request), ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "10")
                .cacheControl(CacheControl.noStore())
                .body(ApiError.of(503, "Service Unavailable",
                        "No pudimos completar la operación ahora. Probá de nuevo en unos segundos.", maskedPath(request)));
    }

    private static ResponseEntity<ApiError> error(HttpStatus status, String reason, String message,
            HttpServletRequest request, List<String> details) {
        ApiError body = details == null
                ? ApiError.of(status.value(), reason, message, maskedPath(request))
                : ApiError.of(status.value(), reason, message, maskedPath(request), details);
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);
    }

    /** The request path with the order token replaced, so error bodies never carry the credential. */
    static String maskedPath(HttpServletRequest request) {
        return request.getRequestURI().replaceFirst("^/packs/orders/[^/]+", "/packs/orders/{token}");
    }
}
