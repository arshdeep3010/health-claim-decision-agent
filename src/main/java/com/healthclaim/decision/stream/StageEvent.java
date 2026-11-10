package com.healthclaim.decision.stream;

import java.util.Map;

/**
 * One progress event of an analysis.
 *
 * @param stage     which pipeline stage
 * @param status    STARTED, COMPLETED, SKIPPED or FAILED
 * @param elapsedMs time the stage took (0 for STARTED/SKIPPED)
 * @param data      stage-specific, non-sensitive details (counts, codes, model name)
 */
public record StageEvent(AnalysisStage stage, Status status, long elapsedMs, Map<String, Object> data) {

    public enum Status { STARTED, COMPLETED, SKIPPED, FAILED }

    public static StageEvent started(AnalysisStage stage) {
        return new StageEvent(stage, Status.STARTED, 0L, Map.of());
    }

    public static StageEvent started(AnalysisStage stage, Map<String, Object> data) {
        return new StageEvent(stage, Status.STARTED, 0L, data);
    }

    public static StageEvent completed(AnalysisStage stage, long elapsedMs, Map<String, Object> data) {
        return new StageEvent(stage, Status.COMPLETED, elapsedMs, data);
    }

    public static StageEvent skipped(AnalysisStage stage, Map<String, Object> data) {
        return new StageEvent(stage, Status.SKIPPED, 0L, data);
    }

    public static StageEvent failed(AnalysisStage stage, long elapsedMs, Map<String, Object> data) {
        return new StageEvent(stage, Status.FAILED, elapsedMs, data);
    }
}
