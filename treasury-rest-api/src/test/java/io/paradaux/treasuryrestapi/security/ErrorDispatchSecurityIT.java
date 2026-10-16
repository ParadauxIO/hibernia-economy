package io.paradaux.treasuryrestapi.security;

import io.paradaux.treasuryrestapi.testsupport.EmbeddedDbIT;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SecurityConfig is default-deny ({@code anyRequest().authenticated()}), but a servlet
 * container ERROR dispatch to {@code /error} (after {@code sendError}, e.g. the firewall
 * rejecting a URL with 400, or an exception escaping a filter) carries no authentication:
 * JwtAuthFilter is a OncePerRequestFilter and skips error dispatches. Without the
 * explicit ERROR-dispatch permit, every such error would be masked as a 401.
 *
 * <p>MockMvc never performs container error dispatches itself, so this builds one by
 * hand: the dispatcher type plus the {@code jakarta.servlet.error.*} attributes Tomcat
 * sets when forwarding to the error page.
 */
class ErrorDispatchSecurityIT extends EmbeddedDbIT {

    @Test
    void errorDispatch_keepsOriginalStatus_notMaskedAs401() throws Exception {
        mvc.perform(get("/error").with(req -> {
                    req.setDispatcherType(DispatcherType.ERROR);
                    req.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 400);
                    req.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/api/v1/accounts/1/balance");
                    req.setAttribute(RequestDispatcher.ERROR_MESSAGE, "rejected by firewall");
                    return req;
                }))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    /** A plain (REQUEST-dispatch) call to /error is not public: it still needs a token. */
    @Test
    void directRequestToErrorPage_needsToken() throws Exception {
        mvc.perform(get("/error"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("MISSING_TOKEN"));
    }
}
