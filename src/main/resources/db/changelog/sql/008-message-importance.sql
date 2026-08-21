-- =====================================================================
-- Message Importance Calculation System
-- =====================================================================
-- This file consolidates all importance calculation logic:
-- 1. Trigger function for automatic importance calculation on bot.messages
-- 2. Batch recalculation function for tgscan.messages
-- =====================================================================

-- ---------------------------------------------------------------------
-- TRIGGER FUNCTION: Auto-calculate importance on INSERT/UPDATE
-- ---------------------------------------------------------------------
-- Applied to bot.messages table for real-time importance calculation
-- Gracefully handles missing channel data with exception handling

CREATE OR REPLACE FUNCTION bot.messages_calculate_importance()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    v_weight         double precision := 0.0;
    v_subscribers    double precision := NULL;
    v_age_hours      double precision := 0.0;
    v_views          bigint           := COALESCE(NEW.views, 0);
    v_forwards       bigint           := COALESCE(NEW.forwards, 0);
    v_importance     double precision;
BEGIN
    -- Skip ranking for system-generated responses
    IF COALESCE(NEW.message_type, '') = 'AI_RESPONSE' THEN
        NEW.importance := NULL;
        RETURN NEW;
    END IF;

    -- Only rank textual messages (mirror Java logic that runs for MessageText events)
    IF ((NEW.content IS NULL OR btrim(NEW.content) = '')
        AND (NEW.caption IS NULL OR btrim(NEW.caption) = '')) THEN
        NEW.importance := NULL;
        RETURN NEW;
    END IF;

    -- Safely attempt to load channel weight and subscribers
    -- Uses exception handling to prevent transaction rollbacks
    BEGIN
        SELECT COALESCE(c.weight, 0.0), c.subscribers::double precision
          INTO v_weight, v_subscribers
          FROM tgscan.channels c
         WHERE c.id = NEW.chat_id;
    EXCEPTION WHEN OTHERS THEN
        -- If lookup fails for any reason, use defaults
        v_weight := 0.0;
        v_subscribers := NULL;
    END;

    IF NEW.date IS NOT NULL THEN
        v_age_hours := GREATEST(
            0.0,
            EXTRACT(EPOCH FROM (timezone('utc', now()) - NEW.date)) / 3600.0
        );
    END IF;

    -- Calculate importance using weighted formula
    v_importance := LEAST(
        1.0,
        GREATEST(
            1e-12,
            (
                0.35 * COALESCE(v_weight, 0.0)
                + 0.25 * (0.7 * COALESCE(NEW.consensus, 0.5) + 0.3 * COALESCE(NEW.novelty, 0.5))
                + 0.20 * (
                    0.6 * tgscan._norm_log(v_views, 10000::bigint)::double precision
                        * LEAST(
                            1.0,
                            CASE
                                WHEN v_subscribers IS NULL OR v_subscribers <= 0 THEN 0.0
                                ELSE v_views::double precision
                                     / NULLIF(GREATEST(1.0, v_subscribers), 0.0)
                            END
                        )
                    + 0.4 * tgscan._norm_log(v_forwards, 300::bigint)::double precision
                )
                + 0.15 * EXP(
                    -LEAST(
                        700.0,
                        LN(2.0) * v_age_hours / 24.0
                    )
                )
            )
        )
    );

    NEW.importance := v_importance;
    RETURN NEW;
END;
$$;

-- Drop and recreate trigger to ensure clean state
DROP TRIGGER IF EXISTS messages_calculate_importance_trg ON bot.messages;

CREATE TRIGGER messages_calculate_importance_trg
BEFORE INSERT OR UPDATE OF content, caption, consensus, novelty, views, forwards, date, chat_id
ON bot.messages
FOR EACH ROW
EXECUTE FUNCTION bot.messages_calculate_importance();

-- ---------------------------------------------------------------------
-- BATCH FUNCTION: Recalculate importance for tgscan.messages
-- ---------------------------------------------------------------------
-- Used by Python scanner for batch importance updates
-- Uses EXP instead of POWER to prevent numeric underflow

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
