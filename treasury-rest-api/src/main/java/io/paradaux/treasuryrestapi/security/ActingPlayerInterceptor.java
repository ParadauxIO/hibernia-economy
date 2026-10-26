package io.paradaux.treasuryrestapi.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Validates {@value ActingPlayer#HEADER} on the SERVICE-only admin surface
 * ({@code /api/v1/admin/**}), exposes it to controllers as a request attribute,
 * and writes one audit line per admin request naming the key and the acting
 * player. A malformed header on a SERVICE call is a 400 before the controller
 * runs; on any other token the header is ignored (those calls are refused with
 * 403 by the security chain anyway).
 */
public class ActingPlayerInterceptor implements HandlerInterceptor {

    private static final Logger audit = LoggerFactory.getLogger("io.paradaux.treasuryrestapi.audit.AdminRequests");

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        ActingPlayer.resolve(principal(), request.getHeader(ActingPlayer.HEADER))
                .ifPresent(uuid -> request.setAttribute(ActingPlayer.ATTRIBUTE, uuid));
        return true;
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        VerifiedToken verified = principal();
        UUID actingPlayer = ActingPlayer.of(request).orElse(null);
        audit.info(
                "admin request method={} path={} status={} keyId={} actingPlayer={}",
                request.getMethod(),
                request.getRequestURI(),
                response.getStatus(),
                verified != null ? verified.keyId() : null,
                actingPlayer != null ? actingPlayer : "none");
    }

    private static VerifiedToken principal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof VerifiedToken vt ? vt : null;
    }
}
