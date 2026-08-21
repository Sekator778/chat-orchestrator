-- =====================================================================
-- Importance-clobber fix (Changeset 074)
-- =====================================================================
-- Problem: PR #109 introduced bot.fn_recompute_importance() as the sole
-- writer of bot.messages.importance (the "Variant B" multiplicative brain).
-- However, changeset 024 redefined tgscan.fn_recalc_importance() to also
-- UPDATE bot.messages with the OLD additive formula. ScoringRefreshScheduledJob
-- calls fn_refresh_all() hourly, which calls fn_recalc_importance() hourly,
-- so bot.messages.importance was flip-flopping between Variant B and the old
-- additive formula. PROOF: live min(bot.messages.importance) ~= 9.5e-06, which
-- is below the Variant B hard floor of 0.15^3 ~= 0.0034 -- only the old additive
-- formula (which floors at 1e-12) can produce values that low.
--
-- Fix: Retarget tgscan.fn_recalc_importance back to tgscan.messages (its
-- original pre-024 target). The formula, signature, and fn_refresh_all call
-- site are preserved unchanged. bot.messages.importance is now written ONLY
-- by bot.fn_recompute_importance (the #109 brain).
-- =====================================================================

-- -----------------------------------------------------------------------
-- Step 1: Retarget fn_recalc_importance to tgscan.messages
--         (restores pre-024 behaviour; uses channel_id + posted_at which
--          match the tgscan.messages schema, not the bot.messages schema)
-- -----------------------------------------------------------------------
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
END;
$$;

-- -----------------------------------------------------------------------
-- Step 2: Immediately restore correct bot.messages.importance values
--         so the #109 Variant B brain is authoritative right after deploy.
-- -----------------------------------------------------------------------
SELECT bot.fn_recompute_importance();
