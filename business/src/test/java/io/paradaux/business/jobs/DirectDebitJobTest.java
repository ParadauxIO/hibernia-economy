package io.paradaux.business.jobs;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.paradaux.business.Business;
import io.paradaux.business.services.DirectDebitService;
import org.junit.jupiter.api.Test;

class DirectDebitJobTest {
    private final DirectDebitService debits = mock(DirectDebitService.class);
    private final DirectDebitJob job = new DirectDebitJob(mock(Business.class), debits);

    @Test
    void runsOnePassPerTickWhetherOrNotAnythingHappened() {
        when(debits.runJob())
                .thenReturn(new DirectDebitService.JobResult(0, 0, 0, 0))
                .thenReturn(new DirectDebitService.JobResult(1, 2, 3, 4));

        job.run();
        job.run();

        verify(debits, times(2)).runJob();
    }

    @Test
    void aFailingPassIsSwallowedSoTheNextTickStillRuns() {
        when(debits.runJob())
                .thenThrow(new IllegalStateException("database unavailable"))
                .thenReturn(new DirectDebitService.JobResult(0, 0, 0, 0));

        assertThatCode(job::run).doesNotThrowAnyException();
        job.run();

        verify(debits, times(2)).runJob();
    }
}
