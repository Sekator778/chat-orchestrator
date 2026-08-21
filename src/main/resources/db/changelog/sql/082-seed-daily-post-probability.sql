-- =============================================================================
-- cs082 — Seed news.proactive-posting.daily-post-probability into bot.app_settings
--
-- Each persona throws a daily coin-flip before posting own-news proactively.
-- This knob controls the probability that ANY post is made on a given UTC day.
-- 0.5 = approximately once every two days per persona per chat.
--
-- ON CONFLICT (name) DO NOTHING — idempotent: an existing hand-set row is
-- preserved unchanged.  value_type='double' matches the getDouble() reader.
-- =============================================================================

INSERT INTO bot.app_settings (name, value, value_type, description)
VALUES (
    'news.proactive-posting.daily-post-probability',
    '0.5',
    'double',
    'Per-day coin-flip probability for proactive own-news posting. '
    'Each (persona, chat) pair draws a stable random value once per UTC day; '
    'if the draw >= this probability the persona stays silent that day. '
    '0.5 = post on ~50% of days (default). 1.0 = post every day. 0.0 = never post.'
)
ON CONFLICT (name) DO NOTHING;
