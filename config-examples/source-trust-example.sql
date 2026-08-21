-- Source Trust Configuration Examples
-- Insert trust scores and categories for common crypto channels

-- =================================================================
-- OFFICIAL EXCHANGES (High Trust: 0.90-0.95)
-- =================================================================

-- Example: Binance Official
INSERT INTO tgscan.source_trust (channel_id, trust_score, is_official, category, manual_override)
VALUES (-1001234567890, 0.95, true, 'EXCHANGE', true)
ON CONFLICT (channel_id) DO UPDATE
SET trust_score = EXCLUDED.trust_score,
    is_official = EXCLUDED.is_official,
    category = EXCLUDED.category,
    manual_override = EXCLUDED.manual_override,
    last_updated = NOW();

-- Example: Coinbase Official
INSERT INTO tgscan.source_trust (channel_id, trust_score, is_official, category, manual_override)
VALUES (-1009876543210, 0.95, true, 'EXCHANGE', true)
ON CONFLICT (channel_id) DO UPDATE
SET trust_score = EXCLUDED.trust_score,
    is_official = EXCLUDED.is_official,
    category = EXCLUDED.category,
    manual_override = EXCLUDED.manual_override,
    last_updated = NOW();

-- =================================================================
-- NEWS MEDIA (Medium-High Trust: 0.70-0.80)
-- =================================================================

-- Example: CoinDesk
INSERT INTO tgscan.source_trust (channel_id, trust_score, is_official, category, manual_override)
VALUES (-1001111111111, 0.75, false, 'NEWS', true)
ON CONFLICT (channel_id) DO UPDATE
SET trust_score = EXCLUDED.trust_score,
    category = EXCLUDED.category,
    last_updated = NOW();

-- Example: CoinTelegraph
INSERT INTO tgscan.source_trust (channel_id, trust_score, is_official, category, manual_override)
VALUES (-1002222222222, 0.75, false, 'NEWS', true)
ON CONFLICT (channel_id) DO UPDATE
SET trust_score = EXCLUDED.trust_score,
    category = EXCLUDED.category,
    last_updated = NOW();

-- Example: The Block
INSERT INTO tgscan.source_trust (channel_id, trust_score, is_official, category, manual_override)
VALUES (-1003333333333, 0.78, false, 'NEWS', true)
ON CONFLICT (channel_id) DO UPDATE
SET trust_score = EXCLUDED.trust_score,
    category = EXCLUDED.category,
    last_updated = NOW();

-- =================================================================
-- INFLUENCERS (Medium Trust: 0.55-0.65)
-- =================================================================

-- Example: Crypto influencer 1
INSERT INTO tgscan.source_trust (channel_id, trust_score, is_official, category, manual_override)
VALUES (-1005555555555, 0.60, false, 'INFLUENCER', true)
ON CONFLICT (channel_id) DO UPDATE
SET trust_score = EXCLUDED.trust_score,
    category = EXCLUDED.category,
    last_updated = NOW();

-- Example: Crypto influencer 2
INSERT INTO tgscan.source_trust (channel_id, trust_score, is_official, category, manual_override)
VALUES (-1006666666666, 0.55, false, 'INFLUENCER', true)
ON CONFLICT (channel_id) DO UPDATE
SET trust_score = EXCLUDED.trust_score,
    category = EXCLUDED.category,
    last_updated = NOW();

-- =================================================================
-- PROJECT CHANNELS (Medium-High Trust: 0.70-0.85)
-- =================================================================

-- Example: Ethereum Official
INSERT INTO tgscan.source_trust (channel_id, trust_score, is_official, category, manual_override)
VALUES (-1007777777777, 0.85, true, 'PROJECT', true)
ON CONFLICT (channel_id) DO UPDATE
SET trust_score = EXCLUDED.trust_score,
    is_official = EXCLUDED.is_official,
    category = EXCLUDED.category,
    last_updated = NOW();

-- Example: Solana Official
INSERT INTO tgscan.source_trust (channel_id, trust_score, is_official, category, manual_override)
VALUES (-1008888888888, 0.80, true, 'PROJECT', true)
ON CONFLICT (channel_id) DO UPDATE
SET trust_score = EXCLUDED.trust_score,
    is_official = EXCLUDED.is_official,
    category = EXCLUDED.category,
    last_updated = NOW();

-- =================================================================
-- COMMUNITY CHANNELS (Medium Trust: 0.40-0.55)
-- =================================================================

-- Example: General crypto community
INSERT INTO tgscan.source_trust (channel_id, trust_score, is_official, category, manual_override)
VALUES (-1009999999999, 0.50, false, 'COMMUNITY', true)
ON CONFLICT (channel_id) DO UPDATE
SET trust_score = EXCLUDED.trust_score,
    category = EXCLUDED.category,
    last_updated = NOW();

-- =================================================================
-- BULK UPDATE: Set default trust for unconfigured channels
-- =================================================================

-- Set default trust for all joined channels without trust score
INSERT INTO tgscan.source_trust (channel_id, trust_score, is_official, category, manual_override)
SELECT c.id, 0.50, false, 'COMMUNITY', false
FROM tgscan.channels c
LEFT JOIN tgscan.source_trust st ON st.channel_id = c.id
WHERE c.join_status = 'JOINED'
  AND st.channel_id IS NULL
ON CONFLICT (channel_id) DO NOTHING;

-- =================================================================
-- VERIFICATION QUERIES
-- =================================================================

-- Check configured sources
SELECT
  c.id,
  c.title,
  c.username,
  st.trust_score,
  st.is_official,
  st.category,
  st.manual_override
FROM tgscan.channels c
JOIN tgscan.source_trust st ON st.channel_id = c.id
WHERE c.join_status = 'JOINED'
ORDER BY st.trust_score DESC, c.title;

-- Count by category
SELECT
  category,
  COUNT(*) as channel_count,
  AVG(trust_score) as avg_trust,
  COUNT(*) FILTER (WHERE is_official) as official_count
FROM tgscan.source_trust
GROUP BY category
ORDER BY avg_trust DESC;

-- Channels without trust scores
SELECT c.id, c.title, c.username
FROM tgscan.channels c
LEFT JOIN tgscan.source_trust st ON st.channel_id = c.id
WHERE c.join_status = 'JOINED'
  AND st.channel_id IS NULL
LIMIT 20;

-- =================================================================
-- NOTES
-- =================================================================
-- Category values: EXCHANGE, NEWS, INFLUENCER, PROJECT, COMMUNITY
-- Trust score range: 0.0 (lowest) to 1.0 (highest)
-- is_official: true for verified/official channels
-- manual_override: true for manually configured trust (prevents auto-updates)
--
-- Replace channel IDs with actual IDs from your tgscan.channels table.
-- To find channel IDs, run:
--   SELECT id, title, username FROM tgscan.channels WHERE join_status = 'JOINED';
