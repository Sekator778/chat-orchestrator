-- Channel scoring v1 rollout: schema extensions, scoring function, and diagnostics

-- ---------------------------------------------------------------------------
-- A1: Extend tgscan.channels with scoring fields
-- ---------------------------------------------------------------------------
ALTER TABLE tgscan.channels
    ADD COLUMN IF NOT EXISTS raw_keyword_score DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS channel_score DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS score_activity DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS score_influence DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS score_relevance DOUBLE PRECISION;

CREATE INDEX IF NOT EXISTS idx_tgscan_channels_channel_score
    ON tgscan.channels (channel_score DESC NULLS LAST);

CREATE INDEX IF NOT EXISTS idx_tgscan_messages_channel_id
    ON tgscan.messages (channel_id);

-- Backfill raw_keyword_score from legacy score column if it exists
UPDATE tgscan.channels
SET raw_keyword_score = COALESCE(raw_keyword_score, score)
WHERE score IS NOT NULL
  AND raw_keyword_score IS NULL;

-- ---------------------------------------------------------------------------
-- A2: Channel scoring function
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION tgscan.fn_recalc_channel_score(p_window_days INT DEFAULT 14)
RETURNS VOID LANGUAGE plpgsql AS $$
BEGIN
  PERFORM tgscan._log('channel_score', 'start', NULL);

  WITH window_messages AS (
    SELECT
      m.channel_id,
      m.posted_at,
      m.views,
      m.forwards,
      m.matched_keywords
    FROM tgscan.messages m
    WHERE m.posted_at >= now() - (p_window_days || ' days')::interval
  ),
  aggregates AS (
    SELECT
      c.id AS channel_id,
      COUNT(wm.*) AS msgs,
      SUM(
        EXP(
          - GREATEST(
              0.0,
              EXTRACT(EPOCH FROM (now() AT TIME ZONE 'utc' - wm.posted_at)) / 3600.0
            ) / 48.0
        )
      ) AS activity_decay,
      AVG(wm.views) AS avg_views,
      AVG(wm.forwards) AS avg_forwards,
      COUNT(*) FILTER (WHERE COALESCE(wm.matched_keywords, '{}') <> '{}') AS msgs_with_markers
    FROM tgscan.channels c
    LEFT JOIN window_messages wm
      ON wm.channel_id = c.id
    GROUP BY c.id
  ),
  unique_marker_counts AS (
    SELECT
      wm.channel_id,
      COUNT(DISTINCT lower(k)) AS unique_markers
    FROM window_messages wm
    CROSS JOIN LATERAL UNNEST(COALESCE(wm.matched_keywords, '{}')) AS k
    GROUP BY wm.channel_id
  ),
  normalized AS (
    SELECT
      agg.channel_id,
      COALESCE(agg.msgs, 0) AS msgs,
      COALESCE(agg.activity_decay, 0.0) AS activity_decay,
      COALESCE(agg.avg_views, 0.0) AS avg_views,
      COALESCE(agg.avg_forwards, 0.0) AS avg_forwards,
      COALESCE(agg.msgs_with_markers, 0) AS msgs_with_markers,
      COALESCE(um.unique_markers, 0) AS unique_markers,
      tgscan._norm_log(COALESCE(agg.avg_views, 0)::bigint, 10000) AS n_views,
      tgscan._norm_log(COALESCE(agg.avg_forwards, 0)::bigint, 300) AS n_forwards,
      CASE
        WHEN COALESCE(agg.msgs, 0) = 0 THEN 0.0
        ELSE LEAST(1.0, GREATEST(0.0, COALESCE(agg.msgs_with_markers, 0)::double precision / agg.msgs))
      END AS share_marked,
      tgscan._norm_log(COALESCE(um.unique_markers, 0)::bigint, 50) AS n_unique_markers,
      CASE
        WHEN COALESCE(agg.activity_decay, 0.0) <= 0 THEN 0.0
        ELSE LN(1.0 + agg.activity_decay) / LN(1.0 + 50.0)
      END AS n_activity
    FROM aggregates agg
    LEFT JOIN unique_marker_counts um
      ON um.channel_id = agg.channel_id
  ),
  channel_calc AS (
    SELECT
      n.channel_id,
      0.6 * n.n_views + 0.4 * n.n_forwards AS score_influence,
      0.6 * n.share_marked + 0.4 * n.n_unique_markers AS score_relevance,
      n.n_activity AS score_activity
    FROM normalized n
  )
  UPDATE tgscan.channels c
  SET
    score_influence = cc.score_influence,
    score_relevance = cc.score_relevance,
    score_activity  = cc.score_activity,
    channel_score   = LEAST(
                        1.0,
                        GREATEST(
                          0.0,
                          0.45 * COALESCE(cc.score_influence, 0.0)
                        + 0.35 * COALESCE(cc.score_relevance, 0.0)
                        + 0.20 * COALESCE(cc.score_activity, 0.0)
                        )
                      )
  FROM channel_calc cc
  WHERE cc.channel_id = c.id;

  PERFORM tgscan._log('channel_score', 'ok', NULL);
END;
$$;

-- ---------------------------------------------------------------------------
-- Refresh pipeline: insert channel scoring step after importance
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION tgscan.fn_refresh_all(
  p_window_days INTEGER,
  p_half_life_hours DOUBLE PRECISION,
  p_limit INTEGER
) RETURNS VOID LANGUAGE plpgsql AS $$
BEGIN
  PERFORM tgscan.fn_update_clusters();
  PERFORM tgscan.fn_update_channel_reliability(p_window_days);
  PERFORM tgscan.fn_recalc_importance(p_half_life_hours);
  PERFORM tgscan.fn_recalc_channel_score(p_window_days);
  PERFORM tgscan.fn_build_agg_top_daily(p_limit);
END;
$$;

CREATE OR REPLACE FUNCTION tgscan.fn_refresh_all(
  p_window_days INTEGER,
  p_half_life_hours INTEGER,
  p_limit INTEGER
) RETURNS VOID LANGUAGE plpgsql AS $$
BEGIN
  PERFORM tgscan.fn_refresh_all(p_window_days, p_half_life_hours::DOUBLE PRECISION, p_limit);
END;
$$;

-- ---------------------------------------------------------------------------
-- A3: Materialized view for analytics/debugging
-- ---------------------------------------------------------------------------
CREATE MATERIALIZED VIEW IF NOT EXISTS tgscan.v_channel_debug AS
SELECT
  c.id,
  c.username,
  c.title,
  c.raw_keyword_score,
  c.score_influence,
  c.score_relevance,
  c.score_activity,
  c.channel_score,
  c.weight,
  COUNT(m.*) FILTER (WHERE m.posted_at >= now() - interval '14 days') AS msgs_14d,
  AVG(m.views) FILTER (WHERE m.posted_at >= now() - interval '14 days') AS avg_views_14d,
  AVG(m.forwards) FILTER (WHERE m.posted_at >= now() - interval '14 days') AS avg_fwd_14d
FROM tgscan.channels c
LEFT JOIN tgscan.messages m
  ON m.channel_id = c.id
GROUP BY
  c.id,
  c.username,
  c.title,
  c.raw_keyword_score,
  c.score_influence,
  c.score_relevance,
  c.score_activity,
  c.channel_score,
  c.weight;
