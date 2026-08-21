-- =====================================================================
-- Web Collector Schema (Changeset 076)
-- =====================================================================
-- Prepares the DB so web articles can be inserted as first-class
-- bot.messages news rows and ranked by the existing brain
-- (bot.fn_recompute_importance, Variant B).
--
-- 1. tgscan.channels.outlet_trust  (nullable double)
--    NULL  = normal Telegram channel (existing behavior unchanged)
--    NOT NULL = web outlet row; the brain change in T4/cs077 will
--    branch on this to compute authority = outlet_trust × quality_floor
--    instead of engagement-rate, since web rows have views=0/forwards=0.
--
-- 2. bot.web_sources table
--    Registry of RSS/Atom feed endpoints. Each row has a synthetic
--    negative channel id in the reserved band -9000000001 … -9999999999.
--    This band sits between basic-group ids (~-5B) and supergroup ids
--    (~-1.003T), making accidental collision with real Telegram ids
--    practically impossible. The canonical check (exact ids) confirms 0
--    collisions (see PR body).
--
-- 3. bot.web_sources seed (8 reputable, live, crypto+finance feeds)
--    synthetic_channel_id: -9000000001 … -9000000008 (sequential)
--    trust = 0.5 (default; will auto-adjust from primary-source rate in T4+)
--    enabled = true
--
-- 4. tgscan.channels seed (one row per web_source)
--    Makes the ranking brain's INNER JOIN succeed.
--    subscribers = 100000 (synthetic, comfortably above the 1000 gate;
--    ln(100000) ≈ 11.5 — value is a weak lever, only 3× vs ln(2)).
--    ON CONFLICT (id) DO NOTHING → idempotent re-runs.
--
-- 5. bot.app_settings knobs (web-collector.*)
--    Seeded with safe defaults; web-collector.enabled = false so the
--    harvester (T3/WebNewsCollectorService) is gated off until T3+T4 land.
--    ON CONFLICT (name) DO NOTHING → idempotent re-runs.
-- =====================================================================

-- -----------------------------------------------------------------------
-- Step 1: Add outlet_trust column to tgscan.channels
-- -----------------------------------------------------------------------
ALTER TABLE tgscan.channels
    ADD COLUMN IF NOT EXISTS outlet_trust double precision;

COMMENT ON COLUMN tgscan.channels.outlet_trust IS
    'NULL for normal Telegram channels. NOT NULL for synthetic web-outlet rows. '
    'Used by the ranking brain (bot.fn_recompute_importance) in T4/cs077 to '
    'compute authority = outlet_trust instead of engagement-rate.';

-- -----------------------------------------------------------------------
-- Step 2: Create bot.web_sources
-- -----------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS bot.web_sources (
    id                  bigserial         PRIMARY KEY,
    outlet_name         varchar(255)      NOT NULL,
    feed_url            varchar(1024)     NOT NULL,
    registrable_domain  varchar(255),
    synthetic_channel_id bigint           NOT NULL UNIQUE,
    trust               double precision  NOT NULL DEFAULT 0.5,
    geo                 varchar(16)       NOT NULL DEFAULT 'GLOBAL',
    enabled             boolean           NOT NULL DEFAULT true,
    created_at          timestamptz       NOT NULL DEFAULT now(),
    updated_at          timestamptz       NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_web_sources_enabled
    ON bot.web_sources (enabled);

COMMENT ON TABLE bot.web_sources IS
    'Registry of RSS/Atom feeds to harvest as first-class bot.messages news rows. '
    'Each row has a synthetic_channel_id in the -9000000001…-9999999999 band '
    'matched by a tgscan.channels row so the ranking brain CAN join it.';

-- -----------------------------------------------------------------------
-- Step 3: Seed bot.web_sources with 8 reputable, live RSS/Atom feeds
-- -----------------------------------------------------------------------
INSERT INTO bot.web_sources
    (outlet_name, feed_url, registrable_domain, synthetic_channel_id, trust, geo, enabled)
VALUES
    -- Crypto majors
    ('CoinDesk',
     'https://www.coindesk.com/arc/outboundfeeds/rss',
     'coindesk.com',       -9000000001, 0.5, 'GLOBAL', true),

    ('Cointelegraph',
     'https://cointelegraph.com/rss',
     'cointelegraph.com',  -9000000002, 0.5, 'GLOBAL', true),

    ('Decrypt',
     'https://decrypt.co/feed',
     'decrypt.co',         -9000000003, 0.5, 'GLOBAL', true),

    ('The Block',
     'https://www.theblock.co/rss.xml',
     'theblock.co',        -9000000004, 0.5, 'GLOBAL', true),

    -- Crypto aggregator / news
    ('CryptoPanic',
     'https://cryptopanic.com/news/rss/',
     'cryptopanic.com',    -9000000005, 0.5, 'GLOBAL', true),

    ('Crypto Briefing',
     'https://cryptobriefing.com/feed/',
     'cryptobriefing.com', -9000000006, 0.5, 'GLOBAL', true),

    ('BeInCrypto',
     'https://beincrypto.com/feed/',
     'beincrypto.com',     -9000000007, 0.5, 'GLOBAL', true),

    -- Finance / macro
    ('Investing.com News',
     'https://www.investing.com/rss/news.rss',
     'investing.com',      -9000000008, 0.5, 'GLOBAL', true)

ON CONFLICT (synthetic_channel_id) DO NOTHING;

-- -----------------------------------------------------------------------
-- Step 4: Seed matching tgscan.channels rows so the ranking brain joins
-- -----------------------------------------------------------------------
-- bot_instance_id is NOT NULL with default ARRAY['2000000001'::text];
-- it will apply automatically. first_seen/last_seen default now().
INSERT INTO tgscan.channels
    (id, title, is_channel, subscribers, outlet_trust)
SELECT
    ws.synthetic_channel_id,
    ws.outlet_name,
    true,
    100000,
    ws.trust
FROM bot.web_sources ws
ON CONFLICT (id) DO NOTHING;

-- -----------------------------------------------------------------------
-- Step 5: Seed app_settings knobs (web-collector.*)
-- -----------------------------------------------------------------------
INSERT INTO bot.app_settings (name, value, value_type, description)
VALUES
    ('web-collector.enabled',
     'false',
     'bool',
     'Master gate for the web RSS collector (WebNewsCollectorService). '
     'Off until T3 (harvester) + T4 (brain support for outlet_trust) are deployed.'),

    ('web-collector.harvest-interval-min',
     '30',
     'int',
     'How often (minutes) the web collector fetches all enabled feed URLs.'),

    ('web-collector.max-items-per-feed',
     '20',
     'int',
     'Maximum number of items to process per feed URL per harvest run.'),

    ('web-collector.dedup-window-hours',
     '168',
     'int',
     'Dedup lookback window (hours = 7 days) for content_simhash-based duplicate detection.'),

    ('web-collector.default-trust',
     '0.5',
     'double',
     'Default outlet_trust seeded for every new web source row. '
     'Will auto-adjust from primary-source rate once T4 auto-trust lands.'),

    ('web-collector.quality-floor',
     '0.4',
     'double',
     'Minimum quality score assigned to web rows (no engagement signal). '
     'Used by the ranking brain (T4/cs077) as the quality term for web outlets.')

ON CONFLICT (name) DO NOTHING;
