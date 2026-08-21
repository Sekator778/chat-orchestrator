-- =====================================================================
-- Web-Trust Ranking Brain (Changeset 077)
-- =====================================================================
-- Makes bot.fn_recompute_importance() score web outlet rows using
-- outlet_trust as the authority signal and a configurable quality-floor,
-- while leaving ALL Telegram channel rows BYTE-FOR-BYTE UNCHANGED.
--
-- Context:
--   T1/cs076 added tgscan.channels.outlet_trust (NULL for every real
--   Telegram channel; 0..1 for synthetic web outlet rows).  Web rows
--   have views=0/forwards=0/no-cluster, so the existing authority and
--   quality formulas collapse to their floors and web articles never
--   surface.  This changeset branches on outlet_trust being NOT NULL so
--   web rows get a meaningful score while TG rows follow the identical
--   code path they always have.
--
-- Surgical changes to the 075 body (applied to BOTH branches):
--
--   1. New declare:  v_quality_floor numeric;
--   2. New knob read:
--        v_quality_floor := bot.fn_setting_num('web-collector.quality-floor', 0.4);
--   3. In authority_cte SELECT: add MAX(tc.outlet_trust) AS outlet_trust
--      (tc is already joined; MAX avoids touching GROUP BY).
--   4. Authority factor input (per-row UPDATE):
--        BEFORE: auth.authority
--        AFTER:  COALESCE(auth.outlet_trust, auth.authority)
--      COALESCE: when outlet_trust IS NULL (every TG channel) the value
--      is auth.authority — identical to the old expression.
--   5. Quality factor input (per-row UPDATE):
--        BEFORE: <Wilson+penetration blend>
--        AFTER:  CASE WHEN auth.outlet_trust IS NOT NULL
--                     THEN v_quality_floor
--                     ELSE <Wilson+penetration blend — EXACT 075 copy>
--                END
--      When outlet_trust IS NULL (TG): the ELSE branch is the 075
--      expression verbatim → no rounding, no reordering, bit-identical.
--
-- Everything else is copied verbatim from 075:
--   • Function signature and language
--   • All other DECLARE variables and knob reads
--   • authority CTE's authority math (GREATEST, AVG, _norm_log, primary_rate)
--   • freshness factor
--   • factor_floor map  (v_factor_floor + (1-v_factor_floor)*x)
--   • size-term branch and optional size-term factor
--   • WHERE clauses and FROM clauses
--   • EXCEPTION WHEN OTHERS block
-- =====================================================================

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
    -- NEW (077): quality floor for web outlet rows
    v_quality_floor         numeric;
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
    -- NEW (077): flat quality score for web outlet rows (no engagement signal available)
    v_quality_floor         := bot.fn_setting_num('web-collector.quality-floor',      0.4);

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
                -- NEW (077): surface outlet_trust so the UPDATE can branch on it
                MAX(tc.outlet_trust) AS outlet_trust,
                tc.subscribers
            FROM bot.messages m
            JOIN tgscan.channels tc ON tc.id = m.chat_id
            WHERE m.chat_id < 0
              AND m.date >= now() - (v_window_hours || ' hours')::interval
            GROUP BY m.chat_id, tc.subscribers
        )
        UPDATE bot.messages m
        SET importance = (
            -- freshness factor (UNCHANGED)
            (v_factor_floor + (1.0 - v_factor_floor) * EXP(-LN(2.0) * GREATEST(0.0, EXTRACT(EPOCH FROM (now() - m.date)) / 3600.0) / GREATEST(1.0, v_half_life_hours::double precision)))
            -- authority factor
            -- NEW (077): use outlet_trust as authority input for web rows;
            --            fall back to computed authority for TG rows (COALESCE on NULL = no change)
            * (v_factor_floor + (1.0 - v_factor_floor) * COALESCE(auth.outlet_trust, auth.authority))
            -- quality factor
            * (
                v_factor_floor + (1.0 - v_factor_floor) * (
                    -- NEW (077): web rows get the flat quality_floor (no engagement signal);
                    --            TG rows (outlet_trust IS NULL) get the EXACT 075 Wilson+penetration blend
                    CASE WHEN auth.outlet_trust IS NOT NULL
                         THEN v_quality_floor::double precision
                         ELSE
                             -- Wilson lower bound (clamps the n=1 saturation artifact)
                             v_quality_wilson_weight::double precision * (
                                 CASE
                                     WHEN COALESCE(m.views, 0) <= 0 THEN 0.0
                                     ELSE GREATEST(0.0,
                                         (
                                             -- FIX #1a: clamp p to [0,1] in the center term
                                             (LEAST(1.0, m.forwards::double precision / NULLIF(m.views, 0))
                                                 + v_z2::double precision / (2.0 * m.views))
                                             - v_wilson_z::double precision
                                               -- FIX #2: GREATEST(0,…) belt-and-suspenders guard on the radicand
                                               * SQRT(GREATEST(0.0,
                                                     (
                                                         -- FIX #1b+#1c: clamp p to [0,1] in both p*(1-p) occurrences
                                                         LEAST(1.0, m.forwards::double precision / NULLIF(m.views, 0))
                                                         * (1.0 - LEAST(1.0, m.forwards::double precision / NULLIF(m.views, 0)))
                                                         + v_z2::double precision / (4.0 * m.views)
                                                     ) / m.views
                                                 ))
                                         ) / (1.0 + v_z2::double precision / m.views)
                                     )
                                 END
                             )
                             -- penetration (kills n=1 Wilson saturation artifact on very small views)
                             + (1.0 - v_quality_wilson_weight::double precision)
                               * LEAST(1.0, m.views::double precision / NULLIF(auth.subscribers, 0))
                    END
                )
            )
            -- optional size term (UNCHANGED)
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
                -- NEW (077): surface outlet_trust so the UPDATE can branch on it
                MAX(tc.outlet_trust) AS outlet_trust,
                tc.subscribers
            FROM bot.messages m
            JOIN tgscan.channels tc ON tc.id = m.chat_id
            WHERE m.chat_id < 0
              AND m.date >= now() - (v_window_hours || ' hours')::interval
            GROUP BY m.chat_id, tc.subscribers
        )
        UPDATE bot.messages m
        SET importance = (
            -- freshness factor (UNCHANGED)
            (v_factor_floor + (1.0 - v_factor_floor) * EXP(-LN(2.0) * GREATEST(0.0, EXTRACT(EPOCH FROM (now() - m.date)) / 3600.0) / GREATEST(1.0, v_half_life_hours::double precision)))
            -- authority factor
            -- NEW (077): use outlet_trust as authority input for web rows;
            --            fall back to computed authority for TG rows (COALESCE on NULL = no change)
            * (v_factor_floor + (1.0 - v_factor_floor) * COALESCE(auth.outlet_trust, auth.authority))
            -- quality factor
            * (
                v_factor_floor + (1.0 - v_factor_floor) * (
                    -- NEW (077): web rows get the flat quality_floor (no engagement signal);
                    --            TG rows (outlet_trust IS NULL) get the EXACT 075 Wilson+penetration blend
                    CASE WHEN auth.outlet_trust IS NOT NULL
                         THEN v_quality_floor::double precision
                         ELSE
                             v_quality_wilson_weight::double precision * (
                                 CASE
                                     WHEN COALESCE(m.views, 0) <= 0 THEN 0.0
                                     ELSE GREATEST(0.0,
                                         (
                                             -- FIX #1a: clamp p to [0,1] in the center term
                                             (LEAST(1.0, m.forwards::double precision / NULLIF(m.views, 0))
                                                 + v_z2::double precision / (2.0 * m.views))
                                             - v_wilson_z::double precision
                                               -- FIX #2: GREATEST(0,…) belt-and-suspenders guard on the radicand
                                               * SQRT(GREATEST(0.0,
                                                     (
                                                         -- FIX #1b+#1c: clamp p to [0,1] in both p*(1-p) occurrences
                                                         LEAST(1.0, m.forwards::double precision / NULLIF(m.views, 0))
                                                         * (1.0 - LEAST(1.0, m.forwards::double precision / NULLIF(m.views, 0)))
                                                         + v_z2::double precision / (4.0 * m.views)
                                                     ) / m.views
                                                 ))
                                         ) / (1.0 + v_z2::double precision / m.views)
                                     )
                                 END
                             )
                             + (1.0 - v_quality_wilson_weight::double precision)
                               * LEAST(1.0, m.views::double precision / NULLIF(auth.subscribers, 0))
                    END
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

-- Recompute importance immediately on deploy so the updated scores take effect.
SELECT bot.fn_recompute_importance();
