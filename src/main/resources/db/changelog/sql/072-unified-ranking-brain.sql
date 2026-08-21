-- =====================================================================
-- Unified Ranking Brain (Variant B — floored multiplicative formula)
-- Changeset 072
-- =====================================================================
-- Supersedes the old additive trigger (bot.messages_calculate_importance)
-- whose two strongest inputs (channel weight, consensus/novelty) were
-- never populated. Implements the backtest-chosen Variant B formula:
--   importance = floor_factor(authority) * floor_factor(quality) * floor_factor(freshness)
-- where floor_factor(x) = floor + (1 - floor) * x
-- All coefficients come from bot.app_settings at runtime — no hardcoded
-- magic numbers in the formula.
-- =====================================================================

-- -----------------------------------------------------------------------
-- Step 1: Remove the old brain
-- -----------------------------------------------------------------------
DROP TRIGGER IF EXISTS messages_calculate_importance_trg ON bot.messages;
DROP FUNCTION IF EXISTS bot.messages_calculate_importance();

-- -----------------------------------------------------------------------
-- Step 2: Config helper functions
-- -----------------------------------------------------------------------

CREATE OR REPLACE FUNCTION bot.fn_setting_num(p_key text, p_default numeric)
RETURNS numeric
LANGUAGE plpgsql STABLE AS
$$
DECLARE
    v_result numeric;
BEGIN
    SELECT value::numeric
      INTO v_result
      FROM bot.app_settings
     WHERE name = p_key;
    RETURN COALESCE(v_result, p_default);
EXCEPTION WHEN OTHERS THEN
    RETURN p_default;
END;
$$;

CREATE OR REPLACE FUNCTION bot.fn_setting_bool(p_key text, p_default boolean)
RETURNS boolean
LANGUAGE plpgsql STABLE AS
$$
DECLARE
    v_val text;
BEGIN
    SELECT value
      INTO v_val
      FROM bot.app_settings
     WHERE name = p_key;
    IF v_val IS NULL THEN
        RETURN p_default;
    END IF;
    RETURN lower(trim(v_val)) IN ('true', '1');
EXCEPTION WHEN OTHERS THEN
    RETURN p_default;
END;
$$;

-- -----------------------------------------------------------------------
-- Step 3: The brain — bot.fn_recompute_importance()
-- -----------------------------------------------------------------------

CREATE OR REPLACE FUNCTION bot.fn_recompute_importance()
RETURNS void
LANGUAGE plpgsql AS
$$
DECLARE
    v_half_life_hours       numeric;
    v_factor_floor          numeric;
    v_wilson_z              numeric;
    v_quality_wilson_weight numeric;
    v_size_term_enabled     boolean;
    v_size_term_norm_cap    numeric;
    v_window_hours          numeric;
    v_fwd_norm_cap          numeric;
    v_authority_floor       numeric;
    -- Wilson pre-computed
    v_z2                    numeric;
BEGIN
    -- ---- Load all knobs from app_settings (with safe defaults) ----
    v_half_life_hours       := bot.fn_setting_num('news.rank.half_life_hours',       36);
    v_factor_floor          := bot.fn_setting_num('news.rank.factor_floor',           0.15);
    v_wilson_z              := bot.fn_setting_num('news.rank.wilson_z',               1.96);
    v_quality_wilson_weight := bot.fn_setting_num('news.rank.quality_wilson_weight',  0.6);
    v_size_term_enabled     := bot.fn_setting_bool('news.rank.size_term_enabled',     false);
    v_size_term_norm_cap    := bot.fn_setting_num('news.rank.size_term_norm_cap',     1000000);
    v_window_hours          := bot.fn_setting_num('news.rank.window_hours',           168);
    v_fwd_norm_cap          := bot.fn_setting_num('news.rank.fwd_norm_cap',           300);
    v_authority_floor       := bot.fn_setting_num('news.rank.authority_floor',        0.05);

    v_z2 := v_wilson_z * v_wilson_z;

    -- ---- One set-based UPDATE with authority CTE ----
    -- The authority CTE groups ALL messages in the window per chat_id (not a
    -- cluster-filtered subset — pool-filtering would degenerate primary_rate to 1.0).
    -- Inner-join to tgscan.channels means messages from channels missing in that
    -- table are skipped (consistent with the distribution-log query's own join).

    IF v_size_term_enabled THEN
        -- With optional size term
        WITH authority_cte AS (
            SELECT
                m.chat_id,
                -- engagement rate
                GREATEST(
                    v_authority_floor::double precision,
                    0.5 * (
                        0.5 * AVG(LEAST(1.0, m.views::double precision / NULLIF(tc.subscribers, 0)))
                        + 0.5 * tgscan._norm_log(
                              AVG(m.forwards::double precision)::bigint,
                              v_fwd_norm_cap::bigint
                          )
                    ) + 0.5 * (
                        COALESCE(
                            COUNT(*) FILTER (WHERE m.is_primary_in_cluster AND m.cluster_id IS NOT NULL)::double precision
                            / NULLIF(COUNT(*) FILTER (WHERE m.cluster_id IS NOT NULL), 0),
                            0.0
                        )
                    )
                ) AS authority,
                tc.subscribers
            FROM bot.messages m
            JOIN tgscan.channels tc ON tc.id = m.chat_id
            WHERE m.chat_id < 0
              AND m.date >= now() - (v_window_hours || ' hours')::interval
            GROUP BY m.chat_id, tc.subscribers
        )
        UPDATE bot.messages m
        SET importance = (
            -- freshness factor
            (v_factor_floor + (1.0 - v_factor_floor) * EXP(-LN(2.0) * GREATEST(0.0, EXTRACT(EPOCH FROM (now() - m.date)) / 3600.0) / GREATEST(1.0, v_half_life_hours::double precision)))
            -- authority factor
            * (v_factor_floor + (1.0 - v_factor_floor) * auth.authority)
            -- quality factor
            * (
                v_factor_floor + (1.0 - v_factor_floor) * (
                    -- Wilson lower bound (clamps the n=1 saturation artifact)
                    v_quality_wilson_weight::double precision * (
                        CASE
                            WHEN COALESCE(m.views, 0) <= 0 THEN 0.0
                            ELSE GREATEST(0.0,
                                (
                                    (m.forwards::double precision / NULLIF(m.views, 0)
                                        + v_z2::double precision / (2.0 * m.views))
                                    - v_wilson_z::double precision
                                      * SQRT(
                                            (
                                                (m.forwards::double precision / NULLIF(m.views, 0))
                                                * (1.0 - m.forwards::double precision / NULLIF(m.views, 0))
                                                + v_z2::double precision / (4.0 * m.views)
                                            ) / m.views
                                        )
                                ) / (1.0 + v_z2::double precision / m.views)
                            )
                        END
                    )
                    -- penetration (kills n=1 Wilson saturation artifact on very small views)
                    + (1.0 - v_quality_wilson_weight::double precision)
                      * LEAST(1.0, m.views::double precision / NULLIF(auth.subscribers, 0))
                )
            )
            -- optional size term
            * (0.5 + 0.5 * tgscan._norm_log(GREATEST(auth.subscribers, 2)::bigint, v_size_term_norm_cap::bigint))
        )
        FROM authority_cte auth
        WHERE m.chat_id = auth.chat_id
          AND m.chat_id < 0
          AND m.date >= now() - (v_window_hours || ' hours')::interval;
    ELSE
        -- Default path (no size term) — size-independence is the design intent
        WITH authority_cte AS (
            SELECT
                m.chat_id,
                GREATEST(
                    v_authority_floor::double precision,
                    0.5 * (
                        0.5 * AVG(LEAST(1.0, m.views::double precision / NULLIF(tc.subscribers, 0)))
                        + 0.5 * tgscan._norm_log(
                              AVG(m.forwards::double precision)::bigint,
                              v_fwd_norm_cap::bigint
                          )
                    ) + 0.5 * (
                        COALESCE(
                            COUNT(*) FILTER (WHERE m.is_primary_in_cluster AND m.cluster_id IS NOT NULL)::double precision
                            / NULLIF(COUNT(*) FILTER (WHERE m.cluster_id IS NOT NULL), 0),
                            0.0
                        )
                    )
                ) AS authority,
                tc.subscribers
            FROM bot.messages m
            JOIN tgscan.channels tc ON tc.id = m.chat_id
            WHERE m.chat_id < 0
              AND m.date >= now() - (v_window_hours || ' hours')::interval
            GROUP BY m.chat_id, tc.subscribers
        )
        UPDATE bot.messages m
        SET importance = (
            -- freshness factor
            (v_factor_floor + (1.0 - v_factor_floor) * EXP(-LN(2.0) * GREATEST(0.0, EXTRACT(EPOCH FROM (now() - m.date)) / 3600.0) / GREATEST(1.0, v_half_life_hours::double precision)))
            -- authority factor
            * (v_factor_floor + (1.0 - v_factor_floor) * auth.authority)
            -- quality factor
            * (
                v_factor_floor + (1.0 - v_factor_floor) * (
                    v_quality_wilson_weight::double precision * (
                        CASE
                            WHEN COALESCE(m.views, 0) <= 0 THEN 0.0
                            ELSE GREATEST(0.0,
                                (
                                    (m.forwards::double precision / NULLIF(m.views, 0)
                                        + v_z2::double precision / (2.0 * m.views))
                                    - v_wilson_z::double precision
                                      * SQRT(
                                            (
                                                (m.forwards::double precision / NULLIF(m.views, 0))
                                                * (1.0 - m.forwards::double precision / NULLIF(m.views, 0))
                                                + v_z2::double precision / (4.0 * m.views)
                                            ) / m.views
                                        )
                                ) / (1.0 + v_z2::double precision / m.views)
                            )
                        END
                    )
                    + (1.0 - v_quality_wilson_weight::double precision)
                      * LEAST(1.0, m.views::double precision / NULLIF(auth.subscribers, 0))
                )
            )
        )
        FROM authority_cte auth
        WHERE m.chat_id = auth.chat_id
          AND m.chat_id < 0
          AND m.date >= now() - (v_window_hours || ' hours')::interval;
    END IF;

EXCEPTION WHEN OTHERS THEN
    RAISE WARNING 'bot.fn_recompute_importance failed: % — importance rows left unchanged', SQLERRM;
END;
$$;

-- -----------------------------------------------------------------------
-- Step 4: Seed knob rows (idempotent)
-- -----------------------------------------------------------------------
INSERT INTO bot.app_settings (name, value, value_type, description) VALUES
    ('news.rank.half_life_hours',       '36',     'double', 'Freshness half-life in hours. After this many hours a message''s freshness factor halves.'),
    ('news.rank.factor_floor',          '0.15',   'double', 'Floor for each multiplicative factor (authority/quality/freshness). Prevents any single factor from zeroing out the score.'),
    ('news.rank.wilson_z',              '1.96',   'double', 'Wilson score confidence z-value (1.96 = 95% CI).'),
    ('news.rank.quality_wilson_weight', '0.6',    'double', 'Weight of Wilson lower bound in the quality blend (1-weight goes to penetration). Penetration blend kills the n=1 Wilson saturation artifact.'),
    ('news.rank.size_term_enabled',     'false',  'bool',   'When true multiplies importance by a log-normalized subscriber count term. Off by default — size-independence is the design intent.'),
    ('news.rank.size_term_norm_cap',    '1000000','long',   'Subscriber cap for the optional size term log-normalisation.'),
    ('news.rank.subscribers_min',       '1000',   'int',    'Informational floor for small-channel filtering (currently unused by fn_recompute_importance; reserved for future junk-filter).'),
    ('news.rank.window_hours',          '168',    'int',    'Recompute window in hours (default 7 days). Only messages newer than this are re-scored.'),
    ('news.rank.fwd_norm_cap',          '300',    'int',    'Forwards log-normalisation cap for the authority eng_rate term.'),
    ('news.rank.authority_floor',       '0.05',   'double', 'Minimum authority value before the factor floor is applied. Ensures even zero-engagement channels get a non-zero authority floor.')
ON CONFLICT (name) DO NOTHING;

-- -----------------------------------------------------------------------
-- Step 5: Immediate backfill (trigger is gone; seed importance now)
-- -----------------------------------------------------------------------
SELECT bot.fn_recompute_importance();
