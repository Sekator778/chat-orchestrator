-- =====================================================================
-- Ranking Brain SQRT-crash fix (Changeset 075)
-- =====================================================================
-- Root cause: Wilson quality term computed p = forwards/views WITHOUT
-- clamping. Real Telegram data can have forwards > views (e.g. views=6,
-- forwards=22 → p=3.67). Then p*(1-p) < 0, the SQRT argument goes
-- negative, and SQRT() throws "cannot take square root of a negative
-- number". Because the full 7-day window is updated in ONE set-based
-- UPDATE, a SINGLE such row aborts the ENTIRE recompute, and the
-- EXCEPTION WHEN OTHERS block swallows the error → importance is left
-- at stale/old values for ALL rows. The ranking brain has effectively
-- never worked since it shipped in #109 / changeset 072.
--
-- Fix: two surgical changes to the Wilson block, applied to BOTH
-- branches (size-term-enabled and default):
--   1. Clamp p to [0,1]: every occurrence of
--        m.forwards::double precision / NULLIF(m.views, 0)
--      that feeds the Wilson formula becomes
--        LEAST(1.0, m.forwards::double precision / NULLIF(m.views, 0))
--      Three occurrences per branch: the center term (lone p) and both
--      p occurrences in the p*(1-p) variance term.
--   2. Belt-and-suspenders SQRT guard: wrap the SQRT argument in
--      GREATEST(0.0, ...) so that even a future edge-case cannot
--      produce a negative radicand.
--
-- Nothing else is changed: signature, knobs, authority CTE, freshness,
-- factor floors, size-term branch logic, EXCEPTION block are all
-- identical to the 072 body.
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

-- Recompute importance immediately on deploy so the corrected scores take
-- effect without waiting for the next scheduled invocation.
SELECT bot.fn_recompute_importance();
