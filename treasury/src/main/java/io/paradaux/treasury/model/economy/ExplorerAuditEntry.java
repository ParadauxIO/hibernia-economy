package io.paradaux.treasury.model.economy;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of the shared {@code explorer_audit} access log (introduced in
 * economy-flyway V3). Used here to record in-game privileged audit-command
 * usage alongside the web explorer's privacy-sensitive reads.
 *
 * <p>Plain {@code @Getter}/{@code @Setter} (no {@code @Data}) so MyBatis can map
 * it without generating equals/hashCode/toString this row never needs.
 */
@Getter
@Setter
@NoArgsConstructor
public class ExplorerAuditEntry {
    private long auditId;
    private Instant at;
    private String actorSub;        // identity used for the access (keycloak sub, or "minecraft:<uuid>")
    private UUID actorUuid;
    private String actorName;
    private String actorRole;       // effective capability used (e.g. the permission node)
    private String method;          // HTTP method, or "CMD" for an in-game command
    private String path;            // request path, or the command that was run
    private String targetType;      // account | transaction | firm | player | chestshop | global
    private String targetId;
    private int outcome;            // HTTP-style status: 200 allowed, 403 denied
    private String sourceIp;
}
