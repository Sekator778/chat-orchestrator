-- =============================================================================
-- cs084 — Living-persona send-pacing knobs (WP3)
--
-- Real-data evidence (279-reply scorecard): the same persona sent two different
-- texts in the same second in the same chat (9 pairs); pending-queue and sibling
-- replies showed no typing cue; the pre-send delay ignored the length of the
-- message being answered; stagger was skipped whenever the random window was
-- off; daily proactive posts fired on an exact top-of-hour cron.
--
-- 1) Seed the four NEW reply_timing.* knobs HumanSendPacer reads (read time,
--    per-(chat,persona) minimum gap, hard cap) plus the proactive jitter knob —
--    all ON CONFLICT (name) DO NOTHING, idempotent.
-- 2) Move three rows still sitting on their OLD seeded (cs064) default forward
--    to the values the owner validated for HumanSendPacer's rollout, guarded so
--    a manually-edited row (value no longer the old default) is left untouched.
-- 3) Fix two descriptions that went stale now that timing applies to every
--    chat, not just reply_timing.test_chat_id.
-- =============================================================================

-- 1) New knobs ---------------------------------------------------------------
INSERT INTO bot.app_settings (name, value, value_type, description)
VALUES
    ('reply_timing.read_ms_per_char',
     '35',
     'int',
     'Simulated reading time per character of the trigger message before a direct reply (a human reads before replying). Capped by reply_timing.read_cap_ms.'),

    ('reply_timing.read_cap_ms',
     '12000',
     'int',
     'Hard cap on the simulated reading-time component of the pre-send delay (ms).'),

    ('reply_timing.min_gap_same_chat_ms',
     '25000',
     'long',
     'Minimum gap enforced between two sends of the same persona in the same chat, so the pre-send delay is raised (never shortened) to avoid same-second bursts.'),

    ('reply_timing.max_pre_send_ms',
     '180000',
     'long',
     'Hard cap on the total pre-send delay (random/read/stagger/gap combined), so a chatty chat never queues a reply minutes past the trigger.'),

    ('proactive.engagement.jitter_max_minutes',
     '20',
     'int',
     'Upper bound (minutes) of the random extra delay added before a daily proactive post, so posts stop landing on the exact top-of-hour tick. 0 disables the jitter (tests).')
ON CONFLICT (name) DO NOTHING;

-- 2) Guarded moves off the old cs064 seed defaults ---------------------------

-- typing.cap_ms: 8000 -> 20000 (only if still on the original seed value)
UPDATE bot.app_settings
   SET value = '20000', updated_at = CURRENT_TIMESTAMP
 WHERE name = 'reply_timing.typing.cap_ms'
   AND value = '8000';

-- random_delay.min_ms: 0 -> 8000, but ONLY while max_ms is still '0' too —
-- i.e. the pair is still the untouched cs064 seed ("feature off").
UPDATE bot.app_settings
   SET value = '8000', updated_at = CURRENT_TIMESTAMP
 WHERE name = 'reply_timing.random_delay.min_ms'
   AND value = '0'
   AND EXISTS (
       SELECT 1 FROM bot.app_settings
        WHERE name = 'reply_timing.random_delay.max_ms' AND value = '0'
   );

-- random_delay.max_ms: 0 -> 45000 (run AFTER the min_ms guard above, which
-- depends on max_ms still reading '0').
UPDATE bot.app_settings
   SET value = '45000', updated_at = CURRENT_TIMESTAMP
 WHERE name = 'reply_timing.random_delay.max_ms'
   AND value = '0';

-- decision_gate.fail_open: true -> false — a gate failure now means silence,
-- not a reply. Description updated together so it never drifts from the value.
UPDATE bot.app_settings
   SET value = 'false',
       description = 'On empty/invalid context or a gate error, stay silent instead of proceeding to fan-out — a gate failure means silence, not a reply; a person who is unsure says nothing.',
       updated_at = CURRENT_TIMESTAMP
 WHERE name = 'decision_gate.fail_open'
   AND value = 'true';

-- 3) Stale descriptions (values unchanged) -----------------------------------

UPDATE bot.app_settings
   SET description = 'Random delay + stagger now apply to every chat; this id only marks the owner''s test chat.',
       updated_at = CURRENT_TIMESTAMP
 WHERE name = 'reply_timing.test_chat_id'
   AND description = 'Only this chat gets the random-delay + stagger timing; all other chats stay on legacy timing.';

UPDATE bot.app_settings
   SET description = 'Typing-indicator duration per character; applies to every chat now (no longer test-chat-only).',
       updated_at = CURRENT_TIMESTAMP
 WHERE name = 'reply_timing.typing.ms_per_char'
   AND description = 'Typing-indicator duration per character (test chat only); the rest keep the legacy heuristic.';
