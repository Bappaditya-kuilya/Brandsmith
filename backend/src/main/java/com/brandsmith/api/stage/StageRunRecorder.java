package com.brandsmith.api.stage;

import java.util.UUID;

import org.springframework.stereotype.Component;

@Component
public class StageRunRecorder {

    private final StageRunRepository repository;

    public StageRunRecorder(StageRunRepository repository) {
        this.repository = repository;
    }

    public Run start(UUID sessionId, String stage, Object input) {
        return new Run(repository.insertRunning(sessionId, stage, input), sessionId, stage);
    }

    public void run(Run run, String status, Object output, String rawResponse, String model,
                    String promptVersion, long latencyMs, int tokensIn, int tokensOut) {
        repository.finish(run.id(), status, output, rawResponse, model, promptVersion,
                latencyMs, tokensIn, tokensOut);
        repository.markDownstreamStale(run.sessionId(), run.stage());
    }

    public void fail(Run run, String error) {
        repository.fail(run.id(), error);
    }

    public record Run(UUID id, UUID sessionId, String stage) {
    }
}
