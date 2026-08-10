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
import io.paradaux.treasuryrestapi.util.SsrfValidator;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

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
    private final WebhookSubscriptionMapper subscriptionMapper;
    private final WebhookDeliveryMapper deliveryMapper;
    private final WebhookCursorMapper cursorMapper;
    private final WebhookRedisLock lock;
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
    @Value("${treasury.webhook.delivery-concurrency:8}")
    private int deliveryConcurrency;

    /** Bounded pool that fans out a tick's due deliveries so one slow/hung endpoint
     *  no longer stalls the rest of the batch (ADT-116). Built in {@link #init()}. */
    private ExecutorService deliveryExecutor;

    public WebhookDispatcherService(LedgerMapper ledgerMapper,
                                    WebhookSubscriptionMapper subscriptionMapper,
                                    WebhookDeliveryMapper deliveryMapper,
                                    WebhookCursorMapper cursorMapper,
                                    WebhookRedisLock lock,
                                    ObjectMapper objectMapper,
                                    @Value("${treasury.webhook.connect-timeout-ms:5000}") long connectTimeoutMs) {
        this.ledgerMapper = ledgerMapper;
        this.subscriptionMapper = subscriptionMapper;
        this.deliveryMapper = deliveryMapper;
        this.cursorMapper = cursorMapper;
        this.lock = lock;
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

    @Scheduled(fixedDelayString = "${treasury.webhook.poll-ms:2000}",
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
        List<Long> accountIds = postings.stream().map(TransactionRow::getAccountId).distinct().toList();
        Map<Long, Set<Long>> subsByAccount = new HashMap<>();
        if (!accountIds.isEmpty()) {
            List<SubscriptionMatch> matches = new ArrayList<>(subscriptionMapper.findAccountMatches(accountIds));
            matches.addAll(subscriptionMapper.findFirmMatches(accountIds));
            for (SubscriptionMatch m : matches) {
                subsByAccount.computeIfAbsent(m.getAccountId(), k -> new HashSet<>()).add(m.getSubscriptionId());
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
            log.info("Webhook ingest: {} txn(s) up to {} → {} deliver(y/ies) enqueued", txnIds.size(), maxTxnId, enqueued);
        }
    }

    private void deliverDue() {
        List<DueDelivery> due = deliveryMapper.findDue(deliveryBatch);
        if (due.isEmpty()) return;

        // Fan the batch out across a bounded pool: each delivery does a blocking
        // HttpClient.send up to httpTimeoutMs, so sending them sequentially let a few
        // slow/hung endpoints stall every later delivery in the batch (and, with
        // @Scheduled fixedDelay, the next tick). Each deliver() records its own
        // outcome and never throws, so invokeAll just waits for the whole batch to
        // settle (ADT-116).
        if (deliveryExecutor == null || due.size() == 1) {
            for (DueDelivery d : due) deliver(d);
            return;
        }
        List<Callable<Void>> tasks = new ArrayList<>(due.size());
        for (DueDelivery d : due) {
            tasks.add(() -> { deliver(d); return null; });
        }
        try {
            deliveryExecutor.invokeAll(tasks);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void deliver(DueDelivery d) {
        URI uri;
        try {
            uri = URI.create(d.getTargetUrl());
        } catch (RuntimeException e) {
            onFailure(d, null, "bad url");
            return;
        }
        // Re-check at send time — DNS could have been re-pointed since registration.
        if (uri.getHost() == null || !SsrfValidator.isPublicHost(uri.getHost())) {
            onFailure(d, null, "url not public");
            return;
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
            if (sc >= 200 && sc < 300) {
                deliveryMapper.markDelivered(d.getDeliveryId(), sc);
                subscriptionMapper.resetFailures(d.getSubscriptionId());
            } else {
                onFailure(d, sc, "HTTP " + sc);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            onFailure(d, null, "interrupted");
        } catch (Exception e) {
            onFailure(d, null, e.getClass().getSimpleName());
        }
    }

    private void onFailure(DueDelivery d, Integer httpStatus, String error) {
        if (d.getAttempts() + 1 >= maxAttempts) {
            deliveryMapper.markFailed(d.getDeliveryId(), httpStatus, truncate(error));
            subscriptionMapper.incrementFailures(d.getSubscriptionId());
            int disabled = subscriptionMapper.disableIfOverThreshold(d.getSubscriptionId(), failureThreshold);
            if (disabled > 0) {
                log.warn("Webhook subscription {} auto-disabled after {} consecutive failed deliveries",
                        d.getSubscriptionId(), failureThreshold);
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
                d.getPluginSystem());
        return new WebhookEvent("transaction", d.getDeliveryId(), d.getSubscriptionId(), d.getAccountId(), item);
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() <= 255 ? s : s.substring(0, 255);
    }
}
