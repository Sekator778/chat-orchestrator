package com.example.telegramuserbot.domain;

/**
 * Channel processing pipeline phases.
 * Each channel progresses through these phases sequentially:
 * RAW → INGESTED → LINKED → CONFIGURED
 */
public enum ProcessingPhase {
    /**
     * Channel discovered but not yet processed.
     * Initial state for new channels from tgscan.channels.
     */
    RAW,

    /**
     * Phase 1 complete: Channel ingested, bot joined, ChatConfig created.
     */
    INGESTED,

    /**
     * Phase 2 complete: Channel relationships (primary_channel_id, discussion) resolved.
     */
    LINKED,

    /**
     * Phase 3 complete: Configuration template applied based on strategy.
     */
    CONFIGURED,

    /**
     * Processing failed with unrecoverable error.
     * Manual intervention required.
     */
    ERROR
}
