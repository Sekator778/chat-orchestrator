-- Additional tgscan schema objects required by Python scanner workflow
CREATE SCHEMA IF NOT EXISTS tgscan;

CREATE TABLE IF NOT EXISTS tgscan.channels (
  id                 BIGINT PRIMARY KEY,
  username           TEXT,
  title              TEXT,
  description        TEXT,
  first_seen         TIMESTAMPTZ DEFAULT now(),
  last_seen          TIMESTAMPTZ DEFAULT now(),
  sample_message     TEXT,
  tags               TEXT[],
  score              DOUBLE PRECISION DEFAULT 0,
  weight             DOUBLE PRECISION,
  subscribers        BIGINT,
  join_status        TEXT,
  join_attempts      INT DEFAULT 0,
  join_last_error    TEXT,
  joined_at          TIMESTAMPTZ,
  mute_status        TEXT,
  muted_at           TIMESTAMPTZ,
  mute_last_error    TEXT,
  reliability_alpha  DOUBLE PRECISION DEFAULT 2,
  reliability_beta   DOUBLE PRECISION DEFAULT 2,
  is_channel         BOOLEAN,
  raw                JSONB
);

CREATE TABLE IF NOT EXISTS tgscan.messages (
  msg_id           BIGINT PRIMARY KEY,
  channel_id       BIGINT REFERENCES tgscan.channels(id) ON DELETE CASCADE,
  posted_at        TIMESTAMPTZ,
  text             TEXT,
  matched_keywords TEXT[],
  views            BIGINT,
  forwards         BIGINT,
  importance       DOUBLE PRECISION,
  content_hash     TEXT,
  cluster_id       TEXT,
  support_count    INT,
  consensus        DOUBLE PRECISION,
  novelty          DOUBLE PRECISION
);

CREATE TABLE IF NOT EXISTS tgscan.channel_candidates (
  id              BIGSERIAL PRIMARY KEY,
  candidate       TEXT NOT NULL,
  source_channel  BIGINT,
  source_msg_id   BIGINT,
  discovered_at   TIMESTAMPTZ DEFAULT now(),
  processed       BOOLEAN DEFAULT FALSE,
  note            TEXT
);

CREATE TABLE IF NOT EXISTS tgscan.agg_top_messages_daily (
  as_of           TIMESTAMPTZ NOT NULL,
  msg_id          BIGINT PRIMARY KEY,
  channel_id      BIGINT NOT NULL,
  posted_at       TIMESTAMPTZ,
  importance      DOUBLE PRECISION,
  consensus       DOUBLE PRECISION,
  novelty         DOUBLE PRECISION,
  views           BIGINT,
  forwards        BIGINT,
  channel_weight  DOUBLE PRECISION,
  username        TEXT,
  title           TEXT,
  preview         TEXT
);

CREATE TABLE IF NOT EXISTS tgscan.run_log (
  id        BIGSERIAL PRIMARY KEY,
  run_at    TIMESTAMPTZ DEFAULT now(),
  step      TEXT,
  status    TEXT,
  details   TEXT
);

CREATE INDEX IF NOT EXISTS idx_tgscan_cc_processed  ON tgscan.channel_candidates(processed);
CREATE INDEX IF NOT EXISTS idx_tgscan_msg_hash      ON tgscan.messages(content_hash);
CREATE INDEX IF NOT EXISTS idx_tgscan_msg_posted_at ON tgscan.messages(posted_at);
CREATE INDEX IF NOT EXISTS idx_tgscan_ch_last_seen  ON tgscan.channels(last_seen);
CREATE INDEX IF NOT EXISTS idx_tgscan_channels_score ON tgscan.channels(score DESC);

CREATE OR REPLACE FUNCTION tgscan._log(p_step TEXT, p_status TEXT, p_details TEXT DEFAULT NULL)
RETURNS VOID LANGUAGE plpgsql AS $$
BEGIN
  INSERT INTO tgscan.run_log(step, status, details) VALUES (p_step, p_status, p_details);
END$$;

CREATE OR REPLACE FUNCTION tgscan.fn_update_clusters()
RETURNS VOID LANGUAGE plpgsql AS $$
BEGIN
  PERFORM tgscan._log('clusters','start',NULL);

  WITH s AS (
    SELECT content_hash AS cluster_id,
           COUNT(DISTINCT channel_id) AS support_count
    FROM tgscan.messages
    WHERE content_hash IS NOT NULL
    GROUP BY 1
  )
  UPDATE tgscan.messages m
  SET cluster_id = m.content_hash,
      support_count = s.support_count,
      consensus = LEAST(1.0, s.support_count / 5.0)
  FROM s
  WHERE m.content_hash = s.cluster_id;

  WITH firsts AS (
    SELECT cluster_id, MIN(posted_at) AS first_time
    FROM tgscan.messages
    WHERE cluster_id IS NOT NULL
    GROUP BY 1
  )
  UPDATE tgscan.messages m
  SET novelty = GREATEST(0.0, 1.0 - EXTRACT(EPOCH FROM (m.posted_at - f.first_time))/3600.0 / 24.0)
  FROM firsts f
  WHERE m.cluster_id = f.cluster_id;

  PERFORM tgscan._log('clusters','ok',NULL);
END$$;

CREATE OR REPLACE FUNCTION tgscan.fn_update_channel_reliability(p_window_days INT DEFAULT 14)
RETURNS VOID LANGUAGE plpgsql AS $$
BEGIN
  PERFORM tgscan._log('reliability','start',NULL);

  WITH q AS (
    SELECT channel_id,
           SUM( (COALESCE(consensus,0) >= 0.6 OR COALESCE(novelty,0) >= 0.8)::int ) AS succ,
           SUM( (COALESCE(consensus,0) <  0.6 AND COALESCE(novelty,0) <  0.8)::int ) AS fail
    FROM tgscan.messages
    WHERE posted_at >= now() - (p_window_days || ' days')::interval
    GROUP BY channel_id
  )
  UPDATE tgscan.channels c
  SET reliability_alpha = GREATEST(1, c.reliability_alpha + q.succ),
      reliability_beta  = GREATEST(1, c.reliability_beta  + q.fail),
      weight            = (c.reliability_alpha) / NULLIF(c.reliability_alpha + c.reliability_beta,0)
  FROM q
  WHERE c.id = q.channel_id;

  PERFORM tgscan._log('reliability','ok',NULL);
END$$;

CREATE OR REPLACE FUNCTION tgscan._norm_log(x BIGINT, ref BIGINT)
RETURNS DOUBLE PRECISION LANGUAGE sql IMMUTABLE AS $$
  SELECT CASE WHEN x IS NULL OR x <= 0 THEN 0
              ELSE LN(1 + x::double precision) / LN(1 + ref::double precision)
         END;
$$;

CREATE OR REPLACE FUNCTION tgscan.fn_recalc_importance(p_half_life_hours DOUBLE PRECISION DEFAULT 12)
RETURNS VOID LANGUAGE plpgsql AS $$
BEGIN
  PERFORM tgscan._log('importance','start',NULL);

  UPDATE tgscan.messages m
  SET importance = LEAST(
    1.0::double precision,
    GREATEST(
      1e-12::double precision,
      (
        0.35::double precision * COALESCE(c.weight, 0.0)::double precision
        +
        0.25::double precision * (
          0.7::double precision * COALESCE(m.consensus, 0.0)::double precision
          +
          0.3::double precision * COALESCE(m.novelty, 0.0)::double precision
        )
        +
        0.20::double precision * (
          0.6::double precision
          * tgscan._norm_log(COALESCE(m.views, 0), 10000)::double precision
          * LEAST(
              1.0::double precision,
              COALESCE(m.views, 0)::double precision
                / NULLIF(GREATEST(1.0::double precision, COALESCE(c.subscribers, 1)::double precision), 0.0::double precision)
            )
          +
          0.4::double precision * tgscan._norm_log(COALESCE(m.forwards, 0), 300)::double precision
        )
        +
        0.15::double precision * EXP(
          -LEAST(
             700.0::double precision,
             LN(2.0::double precision)
             * GREATEST(
                 0.0::double precision,
                 EXTRACT(EPOCH FROM (now() AT TIME ZONE 'utc' - m.posted_at))::double precision / 3600.0::double precision
               )
             / GREATEST(1.0::double precision, p_half_life_hours::double precision)
          )
        )
        +
        0.05::double precision * 0.0::double precision
      )::double precision
    )
  )
  FROM tgscan.channels c
  WHERE c.id = m.channel_id;

  PERFORM tgscan._log('importance','ok',NULL);
END$$;

CREATE OR REPLACE FUNCTION tgscan.fn_build_agg_top_daily(p_limit INT DEFAULT 500)
RETURNS VOID LANGUAGE plpgsql AS $$
BEGIN
  PERFORM tgscan._log('agg_daily','start',NULL);

  DELETE FROM tgscan.agg_top_messages_daily;

  INSERT INTO tgscan.agg_top_messages_daily(
    as_of, msg_id, channel_id, posted_at, importance, consensus, novelty,
    views, forwards, channel_weight, username, title, preview
  )
  SELECT
    now() AT TIME ZONE 'utc' AS as_of,
    m.msg_id, m.channel_id, m.posted_at, m.importance, m.consensus, m.novelty,
    m.views, m.forwards, c.weight, c.username, c.title, LEFT(m.text, 240)
  FROM tgscan.messages m
  JOIN tgscan.channels c ON c.id = m.channel_id
  WHERE m.posted_at >= now() - interval '24 hours'
  ORDER BY m.importance DESC NULLS LAST
  LIMIT p_limit;

  PERFORM tgscan._log('agg_daily','ok',NULL);
END$$;

CREATE OR REPLACE FUNCTION tgscan.fn_refresh_all(p_window_days INTEGER,
                                                 p_half_life_hours DOUBLE PRECISION,
                                                 p_limit INTEGER)
RETURNS VOID LANGUAGE plpgsql AS $$
BEGIN
  PERFORM tgscan.fn_update_clusters();
  PERFORM tgscan.fn_update_channel_reliability(p_window_days);
  PERFORM tgscan.fn_recalc_importance(p_half_life_hours);
  PERFORM tgscan.fn_build_agg_top_daily(p_limit);
END;
$$;

CREATE OR REPLACE FUNCTION tgscan.fn_refresh_all(p_window_days INTEGER,
                                                 p_half_life_hours INTEGER,
                                                 p_limit INTEGER)
RETURNS VOID LANGUAGE plpgsql AS $$
BEGIN
  PERFORM tgscan.fn_refresh_all(p_window_days, p_half_life_hours::DOUBLE PRECISION, p_limit);
END;
$$;
