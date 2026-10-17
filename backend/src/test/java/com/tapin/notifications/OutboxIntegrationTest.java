package com.tapin.notifications;

import com.tapin.TestcontainersConfiguration;
import com.tapin.notifications.email.EmailMessage;
import com.tapin.notifications.email.SendResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, OutboxIntegrationTest.ScriptedSenderConfig.class})
@Testcontainers(disabledWithoutDocker = true)
class OutboxIntegrationTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class ScriptedSenderConfig {
        @Bean
        @Primary
        ScriptedEmailSender scriptedEmailSender() {
            return new ScriptedEmailSender();
        }
    }

    private static final Instant T0 = Instant.parse("2026-10-20T10:00:00Z");

    @Autowired NotificationOutbox outbox;
    @Autowired OutboxProcessor processor;
    @Autowired OutboxRepository repository;
    @Autowired EmailTemplateRenderer renderer;
    @Autowired OutboxProperties properties;
    @Autowired ScriptedEmailSender sender;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("TRUNCATE email_outbox, users RESTART IDENTITY CASCADE");
        sender.reset();
    }

    // ------------------------------------------------------------------ transactional enqueue

    @Test
    void rollbackOfTheBusinessChangeAlsoDiscardsTheEvent() {
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            long userId = insertUser("ana@example.com");
            outbox.enqueue(message(userId, "REGISTRATION_CONFIRMED:reg-1"));
            throw new IllegalStateException("business rule failed after enqueue");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(count("users")).isZero();
        assertThat(count("email_outbox")).isZero();
    }

    @Test
    void enqueueOutsideABusinessTransactionIsRejected() {
        long userId = tx.execute(status -> insertUser("ana@example.com"));

        assertThatThrownBy(() -> outbox.enqueue(message(userId, "k-1")))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void theSameBusinessEventCreatesOnlyOneOutboxRow() {
        long userId = tx.execute(status -> insertUser("ana@example.com"));

        EnqueueResult first = tx.execute(status -> outbox.enqueue(message(userId, "ATTENDANCE_RECORDED:audit-9")));
        EnqueueResult second = tx.execute(status -> outbox.enqueue(message(userId, "ATTENDANCE_RECORDED:audit-9")));

        assertThat(first).isEqualTo(EnqueueResult.QUEUED);
        assertThat(second).isEqualTo(EnqueueResult.DUPLICATE);
        assertThat(second.notificationStatus()).isEqualTo("NOT_REQUIRED");
        assertThat(count("email_outbox")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ provider failure, retry, diagnostics

    @Test
    void providerOutageKeepsTheBusinessChangeAndSchedulesARetry() {
        long id = commitUserAndMessage("ana@example.com", "REGISTRATION_CONFIRMED:reg-1");
        sender.answer(m -> new SendResult.TransientFailure("Connection refused", false));

        OutboxProcessor.BatchResult result = processor.processDue(T0);

        assertThat(result.retried()).isEqualTo(1);
        assertThat(count("users")).isEqualTo(1); // business change survives the outage
        OutboxEntry row = repository.findById(id).orElseThrow();
        assertThat(row.status()).isEqualTo(OutboxStatus.PENDING);
        assertThat(row.attemptCount()).isEqualTo(1);
        assertThat(row.lastError()).contains("Connection refused");
        assertThat(row.nextAttemptAt()).isBetween(T0.plusSeconds(54), T0.plusSeconds(66)); // 1 min +/- 10 %
        assertThat(row.lockedBy()).isNull();

        // Not due yet -> untouched.
        assertThat(processor.processDue(T0.plusSeconds(30)).claimed()).isZero();

        // Provider is back.
        sender.answer(m -> new SendResult.Accepted("provider-42"));
        processor.processDue(T0.plusSeconds(120));

        OutboxEntry sent = repository.findById(id).orElseThrow();
        assertThat(sent.status()).isEqualTo(OutboxStatus.SENT);
        assertThat(sent.attemptCount()).isEqualTo(2);
        assertThat(sent.sentAt()).isEqualTo(T0.plusSeconds(120));
        assertThat(sent.lastError()).isNull();
        assertThat(jdbc.queryForObject("SELECT provider_message_id FROM email_outbox WHERE id = ?", String.class, id))
                .isEqualTo("provider-42");
        // Every attempt of the same row uses the same Message-ID / idempotency key.
        assertThat(sender.sent).extracting(EmailMessage::messageId).containsOnly("<tapin-outbox-" + id + "@tapin.local>");
        assertThat(sender.sent).extracting(EmailMessage::idempotencyKey).containsOnly("REGISTRATION_CONFIRMED:reg-1");
    }

    @Test
    void exhaustedRetriesEndInFailedAndCanBeRequeuedManually() {
        long id = commitUserAndMessage("ana@example.com", "REGISTRATION_CONFIRMED:reg-2");
        sender.answer(m -> new SendResult.TransientFailure("Timeout", true));

        Instant now = T0;
        for (int i = 0; i < properties.maxAttempts(); i++) {
            processor.processDue(now);
            now = now.plus(Duration.ofHours(2));
        }

        OutboxEntry failed = repository.findById(id).orElseThrow();
        assertThat(failed.status()).isEqualTo(OutboxStatus.FAILED);
        assertThat(failed.attemptCount()).isEqualTo(properties.maxAttempts());
        assertThat(failed.lastError()).startsWith("Retries exhausted after 8 attempts").contains("ambiguous");
        assertThat(processor.processDue(now).claimed()).isZero();

        assertThat(outbox.retryFailed(id)).isTrue();
        OutboxEntry requeued = repository.findById(id).orElseThrow();
        assertThat(requeued.status()).isEqualTo(OutboxStatus.PENDING);
        assertThat(requeued.attemptCount()).isZero();
        assertThat(requeued.lastError()).isNotNull(); // diagnostics kept until the next attempt
    }

    @Test
    void permanentFailureIsNotRetriedAndSecretPayloadIsWiped() {
        long userId = tx.execute(status -> insertUser("ana@example.com"));
        long id = tx.execute(status -> {
            outbox.enqueue(new OutboxMessage(EmailEventType.INVITATION, userId, "ana@example.com", null,
                    Map.of("firstName", "Ana", "clubName", "BD Samobor", "invitationUrl", "https://x/pozivnica/SECRET",
                            "expiresAt", "27. 10. 2026."), "INVITATION:inv-1", true));
            return jdbc.queryForObject("SELECT id FROM email_outbox", Long.class);
        });
        sender.answer(m -> new SendResult.PermanentFailure("550 mailbox unavailable"));

        processor.processDue(T0);

        OutboxEntry row = repository.findById(id).orElseThrow();
        assertThat(row.status()).isEqualTo(OutboxStatus.FAILED);
        assertThat(row.attemptCount()).isEqualTo(1);
        assertThat(row.payload()).isEmpty();
        assertThat(sender.sent.getFirst().textBody()).contains("https://x/pozivnica/SECRET");
        assertThat(outbox.retryFailed(id)).isFalse(); // secret is gone: the invitation must be re-issued
    }

    @Test
    void messageThatCannotBeRenderedFailsPermanently() {
        long userId = tx.execute(status -> insertUser("ana@example.com"));
        tx.executeWithoutResult(status -> outbox.enqueue(
                OutboxMessage.of(EmailEventType.PASSWORD_RESET, userId, "ana@example.com", Map.of(), "k-broken")));

        processor.processDue(T0);

        assertThat(jdbc.queryForObject("SELECT status FROM email_outbox", String.class)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT last_error FROM email_outbox", String.class)).contains("firstName");
        assertThat(sender.sent).isEmpty();
    }

    // ------------------------------------------------------------------ concurrency and leases

    @Test
    void twoWorkersNeverProcessTheSameRow() throws Exception {
        long userId = tx.execute(status -> insertUser("ana@example.com"));
        tx.executeWithoutResult(status -> {
            for (int i = 0; i < 60; i++) {
                outbox.enqueue(message(userId, "REGISTRATION_CONFIRMED:bulk-" + i));
            }
        });
        sender.latency(Duration.ofMillis(5));
        OutboxProperties small = new OutboxProperties(properties.pollInterval(), 7, properties.lease(),
                properties.maxAttempts(), properties.initialBackoff(), properties.maxBackoff());
        OutboxProcessor workerA = newProcessor(small, "worker-A");
        OutboxProcessor workerB = newProcessor(small, "worker-B");

        CountDownLatch start = new CountDownLatch(1);
        Callable<Integer> drain = () -> 0;
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            List<Future<Integer>> results = new ArrayList<>();
            for (OutboxProcessor worker : List.of(workerA, workerB)) {
                results.add(pool.submit(() -> {
                    start.await();
                    int total = 0;
                    int claimed;
                    do {
                        claimed = worker.processDue(T0).claimed();
                        total += claimed;
                    } while (claimed > 0);
                    return total;
                }));
            }
            start.countDown();
            int a = results.get(0).get();
            int b = results.get(1).get();
            assertThat(a + b).isEqualTo(60);
        }

        List<String> sentIds = sender.sent.stream().map(EmailMessage::messageId).toList();
        assertThat(sentIds).hasSize(60);
        assertThat(new HashSet<>(sentIds)).hasSize(60); // no row was sent twice
        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_outbox WHERE status = 'SENT' AND attempt_count = 1",
                Long.class)).isEqualTo(60);
    }

    @Test
    void concurrentClaimsReturnDisjointRows() throws Exception {
        long userId = tx.execute(status -> insertUser("ana@example.com"));
        tx.executeWithoutResult(status -> {
            for (int i = 0; i < 20; i++) {
                outbox.enqueue(message(userId, "k-" + i));
            }
        });

        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<List<OutboxEntry>> a = pool.submit(() -> {
                start.await();
                return repository.claimDue("A", T0, T0.plusSeconds(120), 15);
            });
            Future<List<OutboxEntry>> b = pool.submit(() -> {
                start.await();
                return repository.claimDue("B", T0, T0.plusSeconds(120), 15);
            });
            start.countDown();
            Set<Long> idsA = ids(a.get());
            Set<Long> idsB = ids(b.get());

            assertThat(idsA).doesNotContainAnyElementsOf(idsB);
            assertThat(idsA.size() + idsB.size()).isEqualTo(20);
        }
    }

    @Test
    void expiredLeaseIsReclaimedAndTheStaleWorkerCannotOverwriteTheResult() {
        long id = commitUserAndMessage("ana@example.com", "k-lease");
        List<OutboxEntry> claimedByCrashedWorker = repository.claimDue("crashed", T0, T0.plusSeconds(120), 10);
        assertThat(claimedByCrashedWorker).hasSize(1);

        // Lease still valid: nobody else may take it.
        assertThat(processor.processDue(T0.plusSeconds(60)).claimed()).isZero();

        // Lease expired: another worker delivers it.
        assertThat(processor.processDue(T0.plusSeconds(180)).sent()).isEqualTo(1);

        // The stale worker comes back and tries to record a failure: ignored.
        assertThat(repository.markFailed(id, "crashed", T0.plusSeconds(200), "late")).isFalse();
        OutboxEntry row = repository.findById(id).orElseThrow();
        assertThat(row.status()).isEqualTo(OutboxStatus.SENT);
        assertThat(row.attemptCount()).isEqualTo(2);
    }

    // ------------------------------------------------------------------ helpers

    private OutboxProcessor newProcessor(OutboxProperties props, String workerId) {
        return new OutboxProcessor(repository, sender, renderer, props, Clock.systemUTC(), workerId);
    }

    private long commitUserAndMessage(String email, String key) {
        return tx.execute(status -> {
            long userId = insertUser(email);
            outbox.enqueue(message(userId, key));
            return jdbc.queryForObject("SELECT id FROM email_outbox WHERE deduplication_key = ?", Long.class, key);
        });
    }

    private static OutboxMessage message(long userId, String key) {
        return OutboxMessage.of(EmailEventType.PASSWORD_RESET, userId, "ana@example.com",
                Map.of("firstName", "Ana", "resetUrl", "https://tapin.test/reset", "expiresAt", "sutra"), key);
    }

    private long insertUser(String email) {
        return jdbc.queryForObject("""
                INSERT INTO users (email, first_name, last_name, account_status)
                VALUES (?, 'Ana', 'Horvat', 'INVITED') RETURNING id""", Long.class, email);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private static Set<Long> ids(List<OutboxEntry> entries) {
        Set<Long> ids = new HashSet<>();
        entries.forEach(e -> ids.add(e.id()));
        return ids;
    }
}
