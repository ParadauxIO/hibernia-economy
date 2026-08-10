package io.paradaux.treasury.utils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Dedup-key builders for {@code /government pay} and {@code /government payout}.
 *
 * <p>The keys include {@code from}, {@code to}, and {@code amount} so that two
 * <em>distinct</em> transfers fired in the same second from the same sender don't
 * collide on the dedup index. A genuine button-mash (same sender, same args, same
 * second) intentionally still dedupes.
 *
 * <p>Extracted from {@code GovCommand} so this logic can be regression-tested
 * directly — the command class itself is excluded from coverage.
 */
public final class GovDedupKeys {

    private GovDedupKeys() {}

    public static byte[] transfer(UUID sender, int fromAccountId, int toAccountId,
                                  BigDecimal amount, Instant when) {
        return Idempotency.sha256(buildKey("gov-transfer", sender, fromAccountId, toAccountId, amount, when));
    }

    public static byte[] payout(UUID sender, int fromAccountId, int toAccountId,
                                BigDecimal amount, Instant when) {
        return Idempotency.sha256(buildKey("gov-payout", sender, fromAccountId, toAccountId, amount, when));
    }

    public static byte[] adminTransfer(UUID sender, int fromAccountId, int toAccountId,
                                       BigDecimal amount, Instant when) {
        return Idempotency.sha256(buildKey("admin-transfer", sender, fromAccountId, toAccountId, amount, when));
    }

    private static String buildKey(String prefix, UUID sender, int fromAccountId,
                                   int toAccountId, BigDecimal amount, Instant when) {
        // Use the epoch-second as a plain number rather than the ISO-8601 Instant,
        // whose "HH:MM:SS" rendering contains the same ':' used as the field
        // separator. With every field colon-free (prefix, UUID, ints, decimal,
        // long) the ':'-joined key is unambiguously injective (ADT-55).
        return prefix + ":"
                + sender + ":"
                + fromAccountId + ":"
                + toAccountId + ":"
                + amount.toPlainString() + ":"
                + when.truncatedTo(ChronoUnit.SECONDS).getEpochSecond();
    }
}
