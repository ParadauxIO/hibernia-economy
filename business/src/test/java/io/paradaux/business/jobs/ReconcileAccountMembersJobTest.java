package io.paradaux.business.jobs;

import io.paradaux.business.Business;
import io.paradaux.business.services.FirmAccountService;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

class ReconcileAccountMembersJobTest {
    private final FirmAccountService accounts = mock(FirmAccountService.class);
    private final ReconcileAccountMembersJob job = new ReconcileAccountMembersJob(mock(Business.class), accounts);

    @Test
    void worklistFailureDoesNotPreventNextPass() {
        doThrow(new IllegalStateException("database unavailable")).doNothing()
                .when(accounts).reconcileLinkedAccounts();
        assertThatCode(job::run).doesNotThrowAnyException();
        job.run();
        verify(accounts, times(2)).reconcileLinkedAccounts();
    }

    @Test
    void overlappingTickIsSkippedAndLaterTickRuns() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(accounts).reconcileLinkedAccounts();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var first = executor.submit(job);
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                job.run();
                verify(accounts).reconcileLinkedAccounts();
            } finally {
                release.countDown();
            }
            first.get(5, TimeUnit.SECONDS);
        }
        job.run();
        verify(accounts, times(2)).reconcileLinkedAccounts();
    }
}
