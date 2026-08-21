package com.example.telegramuserbot.service.reaction;

import reactor.core.publisher.Mono;

/**
 * Service responsible for executing queued persona reactions via TDLib.
 */
public interface ReactionExecutionService {

    /**
     * Executes pending reactions that are scheduled and due for execution.
     *
     * @return mono of the count of reactions processed in this cycle
     */
    Mono<Integer> executePendingReactions();
}
