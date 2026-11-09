package me.asikeida.docweave.knowledgebase;
import static org.mockito.Mockito.*;
import java.time.Clock;import java.time.Instant;import java.time.ZoneOffset;import java.util.List;
import org.junit.jupiter.api.Test;
import me.asikeida.docweave.knowledgebase.service.*;
import me.asikeida.docweave.knowledgebase.repository.KnowledgeRepository;
class SampleWorkerTests {
    @Test void workerUsesIndependentServiceAndRecordsFailureWithoutSkippingNextUser(){
        var repository=mock(KnowledgeRepository.class);var service=mock(KnowledgeService.class);
        Instant now=Instant.parse("2026-11-01T00:00:00Z");when(repository.pending(now)).thenReturn(List.of("failed-user","next-user"));
        when(service.initialize("failed-user")).thenThrow(new IllegalStateException("synthetic failure"));
        new SampleImportWorker(repository,service,Clock.fixed(now,ZoneOffset.UTC)).consume();
        verify(repository).failed("failed-user",now.plusSeconds(60));verify(service).initialize("next-user");
    }
}
