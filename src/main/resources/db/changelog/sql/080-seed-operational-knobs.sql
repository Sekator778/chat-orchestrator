-- =============================================================================
-- cs080 — Seed validated operational knobs into bot.app_settings
--
-- These rows existed only as hand-set values on staging; a fresh DB would
-- fall back to unsafe code defaults (posting gate too high, web-enrich too
-- high, retention enabled by default).  Migration-managing them makes the
-- safe operational state reproducible on any environment.
--
-- All rows use ON CONFLICT (name) DO NOTHING — idempotent: existing manual
-- rows on staging are preserved unchanged.
-- value_type strings matched from staging: double, int, bool.
-- =============================================================================

INSERT INTO bot.app_settings (name, value, value_type, description)
VALUES
    ('news.proactive-posting.min-value',
     '0.8',
     'double',
     'Minimum value_score a news item must reach to be eligible for proactive posting. '
     'Validated operational default (0.8); code fallback was 1.5 which starved posting.'),

    ('news.web-enrich.min-value',
     '1.0',
     'double',
     'Minimum value_score a news item must reach to trigger web enrichment. '
     'Validated operational default (1.0); code fallback was 2.5 which suppressed enrichment.'),

    ('news.relevance.qdrant-top-k',
     '50000',
     'int',
     'Top-K limit for Qdrant cosine-similarity lookups in relevance scoring. '
     'Validated operational default (50000); ensures broad candidate coverage before id-filtering.'),

    ('retention.enabled',
     'false',
     'bool',
     'Master switch for the daily MessageRetentionScheduler purge. '
     'Validated operational default (false); code fallback was true which enabled destructive DELETEs on fresh DB.'),

    ('retention.days',
     '7',
     'int',
     'Retention window in days: bot.messages rows older than this are deleted when retention.enabled=true. '
     'Validated operational default (7 days).')

ON CONFLICT (name) DO NOTHING;
