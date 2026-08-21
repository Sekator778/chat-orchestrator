package com.example.telegramuserbot.service.orchestration.dto;

import com.example.telegramuserbot.domain.ResponseLength;
import com.example.telegramuserbot.domain.ResponseTone;
import com.example.telegramuserbot.service.decision.ResponseDecisionEngine;

/**
 * Carries shaping directives derived from ResponseDecisionEngine.decide() into the reply pipeline.
 * All fields are nullable — null means "use template default" so downstream code
 * must NOT NPE on any field.
 *
 * <p>Created by the decision-gate integration in KafkaMessageConsumerService when
 * {@code bot.decision-gate.shape-replies=true}. Passed as nullable through the
 * handler chain; null directives == byte-identical current prompt behavior.
 *
 * @param tone          domain ResponseTone mapped from engine ResponseTone (nullable)
 * @param length        domain ResponseLength from the engine decision (nullable)
 * @param intent        engine ResponseIntent (nullable)
 * @param delaySeconds  pre-send delay in seconds decided by the engine (nullable)
 */
public record ResponseDirectives(
        ResponseTone tone,
        ResponseLength length,
        ResponseDecisionEngine.ResponseIntent intent,
        Integer delaySeconds
) {

    /**
     * Maps engine ResponseDecision to ResponseDirectives.
     * Returns null when decision is null (fail-open path) or all directive fields are null,
     * so downstream always uses the template fallback and no NPE is possible.
     *
     * <p>Engine ResponseTone → domain ResponseTone mapping:
     * <ul>
     *   <li>BRIEF → NEUTRAL (short/clipped; closest neutral)</li>
     *   <li>CASUAL → CASUAL</li>
     *   <li>FRIENDLY → FRIENDLY</li>
     *   <li>THOUGHTFUL → SERIOUS (domain "focused and earnest" = thoughtful)</li>
     * </ul>
     *
     * @param decision nullable engine decision (null on fail-open)
     * @return ResponseDirectives, or null if decision is null or fields are empty
     */
    public static ResponseDirectives fromDecision(ResponseDecisionEngine.ResponseDecision decision) {
        // MUST-FIX #6: null guard — fail-open/null decision must return null so
        // downstream falls back to template values, no NPE.
        if (decision == null) {
            return null;
        }

        ResponseDecisionEngine.ResponseTone engineTone = decision.tone();
        ResponseDecisionEngine.ResponseIntent engineIntent = decision.intent();
        ResponseLength domainLength = decision.responseLength(); // already domain type

        // Map engine tone to domain tone
        ResponseTone domainTone = mapTone(engineTone);

        // If everything is null, don't bother — return null so caller skips directives
        if (domainTone == null && domainLength == null && engineIntent == null
                && (decision.delaySeconds() <= 0)) {
            return null;
        }

        Integer delaySeconds = decision.delaySeconds() > 0 ? decision.delaySeconds() : null;

        return new ResponseDirectives(domainTone, domainLength, engineIntent, delaySeconds);
    }

    /**
     * Maps engine-internal ResponseTone to domain ResponseTone.
     * Returns null when engine tone is null, so template tone is used as fallback.
     */
    private static ResponseTone mapTone(ResponseDecisionEngine.ResponseTone engineTone) {
        if (engineTone == null) {
            return null;
        }
        return switch (engineTone) {
            case BRIEF -> ResponseTone.NEUTRAL;      // brief/clipped maps to neutral baseline
            case CASUAL -> ResponseTone.CASUAL;
            case FRIENDLY -> ResponseTone.FRIENDLY;
            case THOUGHTFUL -> ResponseTone.SERIOUS; // "Focused and earnest" = thoughtful
        };
    }
}
