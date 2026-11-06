package io.paradaux.treasuryrestapi.service;

import io.paradaux.treasuryrestapi.dto.TransactionItem;
import io.paradaux.treasuryrestapi.dto.WebhookEvent;
import io.paradaux.treasuryrestapi.mapper.LedgerMapper;
import io.paradaux.treasuryrestapi.mapper.WebhookCursorMapper;
import io.paradaux.treasuryrestapi.mapper.WebhookDeliveryMapper;
import io.paradaux.treasuryrestapi.mapper.WebhookSubscriptionMapper;
import io.paradaux.treasuryrestapi.model.DueDelivery;
import io.paradaux.treasuryrestapi.model.SubscriptionMatch;
import io.paradaux.treasuryrestapi.model.TransactionRow;
import io.paradaux.treasuryrestapi.util.DiscordWebhook;
import io.paradaux.treasuryrestapi.util.HmacSha256;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Tails the shared ledger and delivers new <b>settled</b> transactions to
 * registered webhooks. Runs on a fixed delay; the {@link WebhookRedisLock}
 * lease ensures only one replica is active at a time so deliveries aren't
 * duplicated. Two phases per tick: ingest (cursor → enqueue matching
 * deliveries) and deliver (drain due deliveries with HMAC-signed POSTs,
 * exponential backoff, and auto-disable of dead endpoints).
 *
 * @see io.paradaux.treasuryrestapi.mapper.LedgerMapper#findTransactionsSince for the
 *      settlement-lag reasoning that keeps the {@code txn_id} tail skip-free.
 */
@Service
public class WebhookDispatcherService {

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcherService.class);

    private final LedgerMapper ledgerMapper;
    private final FeatureFlagService flags;
    private final WebhookSubscriptionMapper subscriptionMapper;
    private final WebhookDeliveryMapper deliveryMapper;
    private final WebhookCursorMapper cursorMapper;
    private final WebhookRedisLock lock;
    private final WebhookTargetPolicy targetPolicy;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Value("${treasury.webhook.enabled:true}")
    private boolean enabled;

    @Value("${treasury.webhook.settlement-lag-seconds:3}")
    private int lagSeconds;

    @Value("${treasury.webhook.ingest-batch:500}")
    private int ingestBatch;

    @Value("${treasury.webhook.delivery-batch:100}")
    private int deliveryBatch;

    @Value("${treasury.webhook.http-timeout-ms:10000}")
    private long httpTimeoutMs;

    @Value("${treasury.webhook.max-attempts:5}")
    private int maxAttempts;

    @Value("${treasury.webhook.failure-threshold:15}")
    private long failureThreshold;

    @Value("${treasury.webhook.retry-base-seconds:30}")
    private long retryBaseSeconds;

    @Value("${treasury.webhook.retry-max-seconds:3600}")
    private long retryMaxSeconds;

    /**
     * Hosts whose per-channel limit isn't reported in rate-limit headers (Discord
     * allows roughly 30 posts a minute per channel). Each webhook on them waits
     * {@link #pacedIntervalSeconds} between posts so a busy feed never trips it (PAR-565).
     */
    @Value("${treasury.webhook.paced-hosts:discord.com,discordapp.com,canary.discord.com,ptb.discord.com}")
    private List<String> pacedHosts;

    @Value("${treasury.webhook.paced-interval-seconds:3}")
    private long pacedIntervalSeconds;

    @Value("${treasury.webhook.delivery-concurrency:8}")
    private int deliveryConcurrency;

    /** Bounded pool that fans out a tick's due deliveries so one slow/hung endpoint
     *  no longer stalls the rest of the batch (ADT-116). Built in {@link #init()}. */
    private ExecutorService deliveryExecutor;

    public WebhookDispatcherService(
            LedgerMapper ledgerMapper,
            FeatureFlagService flags,
            WebhookSubscriptionMapper subscriptionMapper,
            WebhookDeliveryMapper deliveryMapper,
            WebhookCursorMapper cursorMapper,
            WebhookRedisLock lock,
            WebhookTargetPolicy targetPolicy,
            ObjectMapper objectMapper,
            @Value("${treasury.webhook.connect-timeout-ms:5000}") long connectTimeoutMs) {
        this.ledgerMapper = ledgerMapper;
        this.flags = flags;
        this.subscriptionMapper = subscriptionMapper;
        this.deliveryMapper = deliveryMapper;
        this.cursorMapper = cursorMapper;
        this.lock = lock;
        this.targetPolicy = targetPolicy;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @PostConstruct
    void init() {
        int threads = Math.max(1, deliveryConcurrency);
        AtomicInteger seq = new AtomicInteger();
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "webhook-deliver-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        this.deliveryExecutor = Executors.newFixedThreadPool(threads, tf);
    }

    @PreDestroy
    void shutdown() {
        if (deliveryExecutor != null) {
            deliveryExecutor.shutdownNow();
        }
    }

    @Scheduled(
            fixedDelayString = "${treasury.webhook.poll-ms:2000}",
            initialDelayString = "${treasury.webhook.initial-delay-ms:10000}")
    public void tick() {
        if (!enabled) return;
        if (!lock.tryAcquireOrRenew()) return;
        try {
            ingest();
            deliverDue();
        } catch (Exception e) {
            log.warn("Webhook dispatcher tick failed: {}", e.toString());
        }
    }

    /** Cursor → new settled txns → enqueue a delivery per matching subscription. */
    private void ingest() {
        Long cur = cursorMapper.get();
        long cursor = cur != null ? cur : 0L;

        List<Long> txnIds = ledgerMapper.findTxnIdsAfter(cursor, lagSeconds, ingestBatch);
        if (txnIds.isEmpty()) return;

        List<TransactionRow> postings = ledgerMapper.findPostingsForTxns(txnIds);

        // Distinct accounts touched by this batch → match to active subscriptions.
        List<Long> accountIds =
                postings.stream().map(TransactionRow::getAccountId).distinct().toList();
        Map<Long, Set<Long>> subsByAccount = new HashMap<>();
        if (!accountIds.isEmpty()) {
            List<SubscriptionMatch> matches = new ArrayList<>(subscriptionMapper.findAccountMatches(accountIds));
            matches.addAll(subscriptionMapper.findFirmMatches(accountIds));
            for (SubscriptionMatch m : matches) {
                subsByAccount
                        .computeIfAbsent(m.getAccountId(), k -> new HashSet<>())
                        .add(m.getSubscriptionId());
            }
        }

        int enqueued = 0;
        if (!subsByAccount.isEmpty()) {
            for (TransactionRow p : postings) {
                Set<Long> subs = subsByAccount.get(p.getAccountId());
                if (subs == null) continue;
                for (long subId : subs) {
                    enqueued += deliveryMapper.enqueue(subId, p.getTxnId(), p.getAccountId());
                }
            }
        }

        long maxTxnId = txnIds.get(txnIds.size() - 1);
        cursorMapper.advanceTo(maxTxnId);
        if (enqueued > 0) {
            log.info(
                    "Webhook ingest: {} txn(s) up to {} → {} deliver(y/ies) enqueued",
                    txnIds.size(),
                    maxTxnId,
                    enqueued);
        }
    }

    private void deliverDue() {
        List<DueDelivery> due = deliveryMapper.findDue(deliveryBatch, flags.isEnabled("treasury.government_api_keys"));
        if (due.isEmpty()) return;

        // One task per subscription, fanned out across a bounded pool: each delivery
        // does a blocking HttpClient.send up to httpTimeoutMs, so sending everything
        // sequentially let a few slow/hung endpoints stall the rest of the batch (and,
        // with @Scheduled fixedDelay, the next tick) (ADT-116). Within a subscription
        // deliveries go one at a time, in due order, so a single endpoint never sees
        // a parallel burst; a rate-limit signal ends that subscription's turn (PAR-563).
        // sendInOrder() records every outcome and never throws, so invokeAll just
        // waits for the whole batch to settle.
        Map<Long, List<DueDelivery>> bySubscription = new LinkedHashMap<>();
        for (DueDelivery d : due) {
            bySubscription
                    .computeIfAbsent(d.getSubscriptionId(), k -> new ArrayList<>())
                    .add(d);
        }
        if (deliveryExecutor == null || bySubscription.size() == 1) {
            bySubscription.values().forEach(this::sendInOrder);
            return;
        }
        List<Callable<Void>> tasks = new ArrayList<>(bySubscription.size());
        for (List<DueDelivery> group : bySubscription.values()) {
            tasks.add(() -> {
                sendInOrder(group);
                return null;
            });
        }
        try {
            deliveryExecutor.invokeAll(tasks);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Sends one subscription's due deliveries in order. When the endpoint asks us to
     * slow down, the rest of its queue (including deliveries not in this batch) moves
     * back by the requested wait and are left untouched otherwise: no attempt used.
     */
    private void sendInOrder(List<DueDelivery> group) {
        for (DueDelivery d : group) {
            // A tick can outlast the lease (each webhook's queue is sent one at a
            // time), and another replica takes over once it expires. Renew before
            // every send and stop the moment we no longer hold it, so two replicas
            // never send the same due delivery.
            if (!lock.tryAcquireOrRenew()) {
                log.warn("Webhook dispatcher lease lost mid-batch; leaving the rest to the new holder");
                return;
            }
            Duration wait = deliver(d);
            if (wait != null) {
                long seconds = Math.max(1, (wait.toMillis() + 999) / 1000);
                deliveryMapper.deferSubscription(d.getSubscriptionId(), seconds);
                return;
            }
        }
    }

    /** Sends one delivery; returns how long its subscription must wait before the next send, or null. */
    private Duration deliver(DueDelivery d) {
        // Recheck when admitting each send; leave paused deliveries pending without consuming attempts.
        if (heldByGovernmentFlag(d) && !flags.isEnabled("treasury.government_api_keys")) return null;
        URI uri;
        try {
            uri = URI.create(d.getTargetUrl());
        } catch (RuntimeException e) {
            onFailure(d, null, "bad url");
            return null;
        }
        // Re-check at send time — DNS could have been re-pointed since registration.
        if (!targetPolicy.isDeliverable(uri)) {
            onFailure(d, null, "url not public");
            return null;
        }

        // Build the body+request and send inside one try so a serialization failure
        // for THIS delivery is accounted for (onFailure) and the rest of the tick's
        // batch still runs — previously a writeValueAsBytes throw escaped the loop
        // and silently skipped every later due delivery in the same tick (ADT-117).
        try {
            // Discord webhooks understand only their own embed shape and ignore our
            // signature/event headers, so we render a rich embed and skip the HMAC
            // (the body isn't our documented event — there's nothing to verify).
            boolean discord = DiscordWebhook.isDiscordWebhook(uri);
            byte[] body = discord
                    ? objectMapper.writeValueAsBytes(DiscordWebhook.toPayload(d))
                    : objectMapper.writeValueAsBytes(toEvent(d));

            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMillis(httpTimeoutMs))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "Treasury-Webhook/1.0")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body));
            if (!discord) {
                builder.header("X-Treasury-Event", "transaction")
                        .header("X-Treasury-Delivery", Long.toString(d.getDeliveryId()))
                        .header("X-Treasury-Signature", HmacSha256.sign(d.getSecret(), body));
            }
            HttpRequest req = builder.build();

            HttpResponse<Void> resp = httpClient.send(req, HttpResponse.BodyHandlers.discarding());
            int sc = resp.statusCode();
            if (sc == 429) {
                // Rate limited: not the endpoint's fault, so no attempt and no failure
                // count. Retry once the advertised window has passed.
                Duration wait = clampWait(rateLimitWait(resp.headers(), true));
                deliveryMapper.markRateLimited(d.getDeliveryId(), sc, "HTTP 429 rate limited");
                log.info(
                        "Webhook subscription {} rate limited; retrying in {}s",
                        d.getSubscriptionId(),
                        wait.toSeconds());
                return wait;
            }
            if (sc >= 200 && sc < 300) {
                deliveryMapper.markDelivered(d.getDeliveryId(), sc);
                subscriptionMapper.resetFailures(d.getSubscriptionId());
                // Discord reports when the bucket is empty; pause before the next send
                // rather than spend a request on a 429. Paced hosts also wait a fixed
                // interval between posts, for the limit they don't report.
                Duration wait = rateLimitWait(resp.headers(), false);
                if (isPaced(uri)) wait = longer(wait, Duration.ofSeconds(pacedIntervalSeconds));
                return wait == null ? null : clampWait(wait);
            }
            onFailure(d, sc, "HTTP " + sc);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            onFailure(d, null, "interrupted");
        } catch (Exception e) {
            onFailure(d, null, e.getClass().getSimpleName());
        }
        return null;
    }

    /**
     * How long the endpoint asked us to wait, from {@code Retry-After} (seconds) or
     * Discord's {@code X-RateLimit-Reset-After} (fractional seconds), whichever is
     * longer. Without a 429 a wait applies only when {@code X-RateLimit-Remaining}
     * is 0. A 429 with no usable header waits {@code retryBaseSeconds}.
     */
    Duration rateLimitWait(java.net.http.HttpHeaders headers, boolean limited) {
        if (!limited) {
            boolean empty = headers.firstValue("X-RateLimit-Remaining")
                    .map(String::trim)
                    .map("0"::equals)
                    .orElse(false);
            if (!empty) return null;
        }
        Duration wait = longer(seconds(headers, "Retry-After"), seconds(headers, "X-RateLimit-Reset-After"));
        if (wait == null) return limited ? Duration.ofSeconds(retryBaseSeconds) : null;
        return wait;
    }

    private boolean isPaced(URI uri) {
        if (pacedIntervalSeconds <= 0 || uri.getHost() == null || pacedHosts == null) return false;
        String host = uri.getHost().toLowerCase(java.util.Locale.ROOT);
        return pacedHosts.stream()
                .map(h -> h.trim().toLowerCase(java.util.Locale.ROOT))
                .anyMatch(host::equals);
    }

    private Duration clampWait(Duration wait) {
        Duration max = Duration.ofSeconds(retryMaxSeconds);
        if (wait.compareTo(max) > 0) return max;
        return wait.isNegative() || wait.isZero() ? Duration.ofSeconds(1) : wait;
    }

    private static Duration seconds(java.net.http.HttpHeaders headers, String name) {
        return headers.firstValue(name)
                .map(v -> {
                    try {
                        double secs = Double.parseDouble(v.trim());
                        return Double.isFinite(secs) && secs >= 0
                                ? Duration.ofMillis((long) Math.ceil(secs * 1000))
                                : null;
                    } catch (NumberFormatException e) {
                        return null; // HTTP-date form: fall back to the default wait
                    }
                })
                .orElse(null);
    }

    private static Duration longer(Duration a, Duration b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.compareTo(b) >= 0 ? a : b;
    }

    /**
     * Webhooks created through a government API key are part of that release and
     * pause with its flag. Explorer-managed government webhooks (no API key) existed
     * before the flag and keep delivering (PAR-561).
     */
    static boolean heldByGovernmentFlag(DueDelivery d) {
        return "GOVERNMENT".equals(d.getKeyType()) && d.getApiKeyId() != null;
    }

    private void onFailure(DueDelivery d, Integer httpStatus, String error) {
        if (d.getAttempts() + 1 >= maxAttempts) {
            deliveryMapper.markFailed(d.getDeliveryId(), httpStatus, truncate(error));
            subscriptionMapper.incrementFailures(d.getSubscriptionId());
            int disabled = subscriptionMapper.disableIfOverThreshold(d.getSubscriptionId(), failureThreshold);
            if (disabled > 0) {
                log.warn(
                        "Webhook subscription {} auto-disabled after {} consecutive failed deliveries",
                        d.getSubscriptionId(),
                        failureThreshold);
            }
        } else {
            long delay = Math.min(retryMaxSeconds, retryBaseSeconds * (1L << d.getAttempts()));
            deliveryMapper.markRetry(d.getDeliveryId(), httpStatus, truncate(error), delay);
        }
    }

    private WebhookEvent toEvent(DueDelivery d) {
        TransactionItem item = new TransactionItem(
                d.getPostingId(),
                d.getTxnId(),
                d.getAmount().toPlainString(),
                d.getMemo(),
                d.getMessage(),
                d.getSettlementTime().toInstant(ZoneOffset.UTC),
                d.getInitiatorUuidBin() != null ? d.getInitiatorUuidBin().toString() : null,
                d.getPluginSystem(),
                TransactionItem.Counterparty.of(
                        d.getCounterpartyAccountId(),
                        d.getCounterpartyAccountType(),
                        d.getCounterpartyDisplayName(),
                        d.getCounterpartyOwnerUuidBin()),
                d.getPayoutCategory());
        return new WebhookEvent("transaction", d.getDeliveryId(), d.getSubscriptionId(), d.getAccountId(), item);
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() <= 255 ? s : s.substring(0, 255);
    }
}
