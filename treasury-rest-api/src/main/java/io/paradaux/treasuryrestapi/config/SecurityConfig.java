package io.paradaux.treasuryrestapi.config;

import io.paradaux.treasuryrestapi.security.JwtAuthFilter;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Single security chain for the core REST API. Every endpoint
 * ({@code /api/v1/transfers}, {@code /api/v1/auth}, {@code /api/v1/accounts/**},
 * {@code /api/v1/firms/**}) is guarded by the in-game HS256 {@link JwtAuthFilter},
 * which authenticates an API-key JWT into a {@code VerifiedToken} principal.
 *
 * <p><b>Default deny.</b> The public surface is an explicit allowlist below; anything
 * else needs a verified token ({@code anyRequest().authenticated()}), so a new
 * controller is protected unless someone deliberately opens it. The allowlist must stay
 * in step with {@link JwtAuthFilter#shouldNotFilter} (the filter skips exactly these
 * prefixes) and with {@code RouteSecurityIT}'s reviewed PUBLIC table, which fails on
 * any route that is neither allowlisted nor rejected without a token.
 *
 * <p>The explorer UI and its Keycloak OIDC chain used to live here too; that
 * surface now belongs to the standalone {@code economy-explorer} project, which
 * reads the shared database directly.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final ObjectMapper objectMapper;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter, ObjectMapper objectMapper) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.objectMapper = objectMapper;
    }

    @Bean
    public SecurityFilterChain apiChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                // No .cors() here on purpose: this API is reached only server-to-server
                // (the explorer's Next.js backend, the in-game plugins) with a Bearer JWT,
                // never fetched directly from a browser, so there are no cross-origin
                // preflights to answer. The Envoy gateway in front terminates TLS and is
                // where any browser-facing CORS policy would live if the UI ever called us
                // directly. Spring's default (no CORS handling) correctly rejects stray
                // cross-origin browser calls rather than reflecting their Origin
                // (ADT no-cors-config-for-browser-ui).
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Container error dispatches (sendError → /error, e.g. a URL the
                        // firewall rejects with 400) carry no authentication — the JWT filter
                        // is a OncePerRequestFilter and skips them. The original request was
                        // already authorised; letting the dispatch through keeps its real
                        // status instead of masking it as 401.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        // Public, read-only ChestShop market data — no JWT (JwtAuthFilter
                        // skips this prefix; throttled by IP via anonymousPerMinute).
                        .requestMatchers("/api/v1/chestshop/**").permitAll()
                        // Public published server config (tax rates) — same treatment.
                        .requestMatchers("/api/v1/config/**").permitAll()
                        // Liveness/readiness probes, info and the Prometheus scrape. In
                        // prod/UAT these sit on the separate management port (8081), which
                        // reuses this chain; only health/info/prometheus are exposed.
                        .requestMatchers("/actuator/**").permitAll()
                        // The published OpenAPI document and Swagger UI. (The .yaml variant,
                        // /v3/api-docs.yaml, is not skipped by JwtAuthFilter and so has
                        // always needed a token; left as-is.)
                        .requestMatchers("/v3/api-docs", "/v3/api-docs/**",
                                "/swagger-ui.html", "/swagger-ui/**").permitAll()
                        // SERVICE-only admin surface, enforced at the URL as well as in each
                        // admin service (AdminScope.require / requireServiceKey): a new admin
                        // endpoint that forgets the service-level check is still closed, and
                        // a non-SERVICE key is refused before its request body is parsed.
                        .requestMatchers("/api/v1/admin/**")
                                .hasAuthority(JwtAuthFilter.authorityFor("SERVICE"))
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e
                        // Same envelope and codes as JwtAuthFilter / AdminScope. The entry
                        // point is a backstop: the filter already 401s every non-public
                        // request without a valid token before authorisation runs.
                        .authenticationEntryPoint((req, res, ex) -> JwtAuthFilter.writeError(objectMapper, res,
                                HttpStatus.UNAUTHORIZED, "MISSING_TOKEN",
                                "Authorization header with a Bearer token is required."))
                        .accessDeniedHandler((req, res, ex) -> JwtAuthFilter.writeError(objectMapper, res,
                                HttpStatus.FORBIDDEN, "FORBIDDEN", "This endpoint requires a SERVICE API key.")))
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable);
        return http.build();
    }
}
