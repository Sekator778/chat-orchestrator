-- =============================================================================
-- cs079 — Hacker News collector: synthetic channel + app_settings knobs
--
-- Platform band: -8_000_000_001 (HN).
-- Distinct from TG basic groups (~-5_000_000_000) and web RSS (-9_000_000_001..-9_999_999_999).
--
-- outlet_trust is intentionally left NULL: HN ranks via real engagement
-- (views=score, forwards=comments) using the normal authority/quality formula in
-- fn_recompute_importance. A non-null outlet_trust would route through the web
-- flat-trust path (cs077), bypassing the engagement-based ranking.
-- =============================================================================

-- -----------------------------------------------------------------------
-- Step 1: Seed the HN synthetic channel row in tgscan.channels
-- -----------------------------------------------------------------------
-- subscribers=2000: intentionally small — just above the 1000-gate.
--
-- WHY NOT a large value (e.g. 50000)?
-- fn_recompute_importance (cs077) computes per-channel AUTHORITY as:
--   0.5 * (0.5 * AVG(views/subscribers) + 0.5 * norm_log(avg_forwards, 300))
-- The penetration term views/subscribers is the dominant signal for HN,
-- whose raw engagement (score=100-2000, comments=10-500) is genuine but small
-- compared to TG megachannels. With subscribers=50000, penetration≈0.003
-- and authority collapses to authority_floor (0.05), making value_score too
-- low to clear the proactive-posting min-value gate even for top HN stories.
-- With subscribers=2000, penetration(score=300)≈0.15 → authority≈0.16 →
-- value_score≈0.93 (clears the staging 0.8 gate), with quality driven by
-- the Wilson term on the real forwards/views ratio.
--
-- outlet_trust = NULL → normal authority/quality path in fn_recompute_importance.
-- bot_instance_id has a NOT NULL default ARRAY['2000000001'::text].
-- first_seen/last_seen default to now().
INSERT INTO tgscan.channels
    (id, title, is_channel, subscribers)
VALUES
    (-8000000001, 'Hacker News', true, 2000)
ON CONFLICT (id) DO NOTHING;

-- -----------------------------------------------------------------------
-- Step 2: Seed app_settings knobs (hn-collector.*)
-- -----------------------------------------------------------------------
-- value_type column (NOT "type" — that column name does not exist).
-- All rows are ON CONFLICT DO NOTHING — idempotent re-runs.
INSERT INTO bot.app_settings (name, value, value_type, description)
VALUES
    ('hn-collector.enabled',
     'false',
     'bool',
     'Master runtime gate for the Hacker News collector (HnNewsCollectorService). '
     'Gated OFF by default; also requires the hn-collector.enabled Spring property=true '
     'for the bean to be registered at all. Enable on staging after first deploy.'),

    ('hn-collector.interval-ms',
     '600000',
     'int',
     'Harvest cadence in milliseconds for the HN collector (default 10 minutes). '
     'Controls both fixedDelay and initialDelay of the @Scheduled tick.'),

    ('hn-collector.max-stories',
     '30',
     'int',
     'Maximum number of top-story ids to fetch from the HN Firebase API per tick. '
     'HN returns up to ~500 ids; 30 covers the genuinely trending stories.')

ON CONFLICT (name) DO NOTHING;
