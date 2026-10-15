package io.paradaux.treasuryrestapi.security;

import tools.jackson.databind.ObjectMapper;
import io.paradaux.treasuryrestapi.dto.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

/**
 * Intercepts every request, extracts the Bearer token, and runs the verification pipeline.
 * On success, populates the SecurityContext with a {@link VerifiedToken} principal.
 * On failure, writes the JSON error response directly and halts the filter chain.
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtTokenVerifier tokenVerifier;
    private final ObjectMapper objectMapper;

    public JwtAuthFilter(JwtTokenVerifier tokenVerifier, ObjectMapper objectMapper) {
        this.tokenVerifier = tokenVerifier;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Derive the app-relative path from the request URI minus any servlet
        // context path. This is robust whether or not a context-path is configured
        // (and unlike getServletPath() it is populated under MockMvc).
        String ctx = request.getContextPath();
        String uri = request.getRequestURI();
        String path = (ctx != null && !ctx.isEmpty() && uri.startsWith(ctx))
                ? uri.substring(ctx.length()) : uri;
        return path.startsWith("/actuator/")  || path.equals("/actuator")
                || path.startsWith("/swagger-ui/") || path.equals("/swagger-ui.html")
                || path.startsWith("/v3/api-docs/") || path.equals("/v3/api-docs")
                // Public ChestShop market endpoints: no Bearer token required. The
                // rate limiter throttles these by client IP (anonymousPerMinute).
                || path.startsWith("/api/v1/chestshop/") || path.equals("/api/v1/chestshop")
                // Published server config (tax rates): public, same IP throttling.
                || path.startsWith("/api/v1/config/") || path.equals("/api/v1/config");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // Step 1: Authorization header present and prefixed with "Bearer "
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            writeError(response, HttpStatus.UNAUTHORIZED, "MISSING_TOKEN",
                    "Authorization header with a Bearer token is required.");
            return;
        }

        String token = authHeader.substring(7);

        try {
            VerifiedToken verified = tokenVerifier.verify(token);
            var auth = new UsernamePasswordAuthenticationToken(verified, null, Collections.emptyList());
            SecurityContextHolder.getContext().setAuthentication(auth);
            filterChain.doFilter(request, response);
        } catch (TokenException e) {
            writeError(response, e.getStatus(), e.getErrorCode(), e.getMessage());
        }
    }

    private void writeError(HttpServletResponse response, HttpStatus status,
                            String errorCode, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), new ErrorResponse(errorCode, message));
    }
}
