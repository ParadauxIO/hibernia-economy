package io.paradaux.treasuryrestapi.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.info.License;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

/**
 * Global OpenAPI metadata. Drives both the raw spec at {@code /v3/api-docs}
 * and any rendered API reference (springdoc Swagger UI, Scalar, etc.).
 */
@Configuration
@OpenAPIDefinition(
        info =
                @Info(
                        title = "Treasury REST API",
                        version = "v1",
                        description = """
                        HTTP surface for the DemocracyCraft Treasury economy. Players issue an \
                        API key in-game with `/treasuryapi`, then use the token to authenticate \
                        every call here.

                        **Two token scopes:**
                        - **PERSONAL** — JWT carries an `acc` claim. Source of every transfer is \
                          the player's PERSONAL account.
                        - **BUSINESS** — JWT carries a `firm` claim. The caller picks one of the \
                          firm's accounts to debit via the `fromAccountId` body field; the firm \
                          ownership check happens server-side. An optional `acc` claim restricts the key \
                          to that single account, including subaccounts.

                        **Money is always a decimal string.** Never JSON `number` — IEEE 754 \
                        rounding will silently corrupt amounts at scale.

                        **Errors share a flat envelope** `{ "error": "SNAKE_CASE_CODE", "message": "..." }`. \
                        The `error` code is stable across versions; the message is informational.
                        """,
                        contact = @Contact(name = "DemocracyCraft", url = "https://democracycraft.net"),
                        license = @License(name = "Proprietary")),
        // No explicit `servers`: springdoc derives the base URL from the request
        // (honouring X-Forwarded-* via forward-headers-strategy: framework), so it
        // resolves to the externally-visible base — root in UAT, /economy in prod.
        // An explicit "/api/v1" double-prefixed the already-/api/v1 paths and broke
        // Swagger "Try it out".
        security = @SecurityRequirement(name = "BearerJWT"))
@SecurityScheme(
        name = "BearerJWT",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT",
        in = SecuritySchemeIn.HEADER,
        description = """
                Pass the full JWT issued by `/treasuryapi personal issue` or \
                `/treasuryapi business issue` in the `Authorization: Bearer <jwt>` \
                header.
                """)
public class OpenApiConfig {}
