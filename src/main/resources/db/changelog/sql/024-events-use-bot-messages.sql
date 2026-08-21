-- Redirect event detection and batch importance calculations to bot.messages.

-- =====================================================================
-- Event detection now uses bot.messages (real-time ingestion table)
-- =====================================================================
CREATE OR REPLACE FUNCTION tgscan.fn_detect_events(
    p_window_minutes INT DEFAULT 15,
    p_min_confidence DOUBLE PRECISION DEFAULT 0.45
)
RETURNS INTEGER
LANGUAGE plpgsql
AS $$
DECLARE
    v_now TIMESTAMPTZ := now();
    v_window_start TIMESTAMPTZ := v_now - (p_window_minutes * interval '1 minute');
    v_inserted INTEGER := 0;
BEGIN
    PERFORM tgscan._log('event_detect', 'start', format('window=%s', p_window_minutes));

    WITH params AS (
        SELECT v_window_start AS window_start,
               v_now AS window_end
    ),
    window_messages AS (
        SELECT
            m.message_id        AS msg_id,
            m.chat_id           AS source_chat_id,
            COALESCE(c.username, '') AS username,
            m."date"            AS posted_at,
            COALESCE(m.importance, 0.0) AS importance,
            COALESCE(
                NULLIF(m.content, ''),
                NULLIF(m.caption, ''),
                '(no text)'
            )                  AS text,
            lower(kw)          AS topic
        FROM bot.messages m
        JOIN params p
          ON m."date" >= p.window_start
         AND m."date" < p.window_end
        LEFT JOIN tgscan.channels c ON c.id = m.chat_id
        CROSS JOIN LATERAL UNNEST(COALESCE(m.matched_keywords, '{}')) kw
    ),
    topic_stats AS (
        SELECT
            topic,
            COUNT(*) AS message_count,
            COUNT(DISTINCT source_chat_id) AS unique_sources,
            AVG(importance) AS avg_importance,
            AVG(CASE WHEN importance <= 0.2 THEN 1 ELSE 0 END)::double precision AS panic_ratio
        FROM window_messages
        GROUP BY topic
    ),
    baseline_windows AS (
        SELECT
            lower(kw) AS topic,
            FLOOR(EXTRACT(EPOCH FROM (p.window_start - m."date")) / (p_window_minutes * 60.0)) AS bucket,
            COUNT(*) AS window_count
        FROM bot.messages m
        JOIN params p
          ON m."date" >= p.window_start - (p_window_minutes * 12) * interval '1 minute'
         AND m."date" < p.window_start
        CROSS JOIN LATERAL UNNEST(COALESCE(m.matched_keywords, '{}')) kw
        GROUP BY 1, 2
    ),
    baseline AS (
        SELECT topic,
               COALESCE(AVG(window_count), 0.0)::double precision AS expected_count
        FROM baseline_windows
        GROUP BY topic
    ),
    ranked_sources AS (
        SELECT
            agg.*,
            ROW_NUMBER() OVER (PARTITION BY agg.topic ORDER BY agg.msg_count DESC, agg.source_chat_id) AS rn
        FROM (
            SELECT
                wm.topic,
                wm.source_chat_id,
                COALESCE(MAX(wm.username), '') AS username,
                COUNT(*) AS msg_count
            FROM window_messages wm
            GROUP BY wm.topic, wm.source_chat_id
        ) agg
    ),
    top_sources AS (
        SELECT
            topic,
            jsonb_agg(
                jsonb_build_object(
                    'channel_id', source_chat_id,
                    'username', username,
                    'message_count', msg_count
                )
                ORDER BY rn
            ) AS sources
        FROM ranked_sources
        WHERE rn <= 5
        GROUP BY topic
    ),
    evidence_ranked AS (
        SELECT
            wm.topic,
            wm.msg_id,
            wm.source_chat_id,
            wm.posted_at,
            wm.importance,
            LEFT(wm.text, 240) AS preview,
            ROW_NUMBER() OVER (
                PARTITION BY wm.topic
                ORDER BY wm.importance DESC, wm.posted_at DESC
            ) AS rn
        FROM window_messages wm
    ),
    evidence AS (
        SELECT
            topic,
            jsonb_agg(
                jsonb_build_object(
                    'msg_id', msg_id,
                    'channel_id', source_chat_id,
                    'posted_at', posted_at,
                    'importance', importance,
                    'preview', preview
                )
                ORDER BY rn
            ) AS evidence
        FROM evidence_ranked
        WHERE rn <= 5
        GROUP BY topic
    ),
    top_message AS (
        SELECT topic, preview AS summary
        FROM evidence_ranked
        WHERE rn = 1
    ),
    topic_triggers AS (
        SELECT
            topic,
            BOOL_OR(position('listing' in text) > 0 OR position('листинг' in text) > 0
                    OR position('ipo' in text) > 0 OR position('upgrade' in text) > 0) AS has_listing,
            BOOL_OR(position('panic' in text) > 0 OR position('dump' in text) > 0
                    OR position('default' in text) > 0 OR position('sanction' in text) > 0
                    OR position('санкц' in text) > 0) AS has_panic
        FROM (
            SELECT topic, lower(text) AS text
            FROM window_messages
        ) wm
        GROUP BY topic
    ),
    candidates AS (
        SELECT
            ts.topic,
            ts.message_count,
            ts.unique_sources,
            COALESCE(ts.avg_importance, 0.0) AS avg_importance,
            COALESCE(ts.panic_ratio, 0.0) AS panic_ratio,
            CASE
                WHEN COALESCE(b.expected_count, 0.0) < 0.01 THEN ts.message_count::double precision
                ELSE ts.message_count::double precision / b.expected_count
            END AS spike_ratio,
            COALESCE(src.sources, '[]'::jsonb) AS top_sources,
            COALESCE(ev.evidence, '[]'::jsonb) AS evidence,
            COALESCE(tm.summary, format('Spike detected for %s (%s messages)', ts.topic, ts.message_count)) AS root_cause,
            COALESCE(tr.has_listing, false) AS has_listing_trigger,
            COALESCE(tr.has_panic, false) AS has_panic_trigger
        FROM topic_stats ts
        LEFT JOIN baseline b ON b.topic = ts.topic
        LEFT JOIN top_sources src ON src.topic = ts.topic
        LEFT JOIN evidence ev ON ev.topic = ts.topic
        LEFT JOIN top_message tm ON tm.topic = ts.topic
        LEFT JOIN topic_triggers tr ON tr.topic = ts.topic
    ),
    final_candidates AS (
        SELECT
            c.*,
            LEAST(
                1.0,
                0.6 * LEAST(1.0, c.spike_ratio / 3.0) +
                0.2 * LEAST(1.0, c.avg_importance) +
                0.2 * LEAST(1.0, c.unique_sources::double precision / 10.0)
            ) AS confidence
        FROM candidates c
    ),
    to_insert AS (
        SELECT
            CASE
                WHEN fc.panic_ratio >= 0.5 OR fc.has_panic_trigger THEN 'FUD/PANIC'
                WHEN fc.has_listing_trigger AND fc.spike_ratio >= 2.0 THEN 'FOMO/LISTING'
                ELSE 'SPIKE'
            END AS event_type,
            fc.topic,
            fc.message_count,
            fc.unique_sources,
            fc.avg_importance,
            fc.panic_ratio,
            fc.spike_ratio,
            fc.top_sources,
            fc.root_cause,
            fc.confidence,
            CASE
                WHEN fc.confidence >= 0.75 THEN 'high'
                WHEN fc.confidence >= 0.5 THEN 'medium'
                ELSE 'low'
            END AS severity,
            fc.evidence
        FROM final_candidates fc
        WHERE fc.message_count >= 3
          AND fc.confidence >= p_min_confidence
    )
    INSERT INTO tgscan.events (
        event_type,
        topic,
        window_start,
        window_end,
        message_count,
        unique_sources,
        avg_importance,
        panic_ratio,
        spike_ratio,
        top_sources,
        root_cause,
        confidence,
        severity,
        evidence,
        rate_limit_key,
        updated_at
    )
    SELECT
        ti.event_type,
        ti.topic,
        p.window_start,
        p.window_end,
        ti.message_count,
        ti.unique_sources,
        ti.avg_importance,
        ti.panic_ratio,
        ti.spike_ratio,
        ti.top_sources,
        ti.root_cause,
        ti.confidence,
        ti.severity,
        ti.evidence,
        format('%s:%s', ti.event_type, ti.topic),
        now()
    FROM to_insert ti
    JOIN params p ON TRUE
    WHERE NOT EXISTS (
        SELECT 1
        FROM tgscan.events e
        WHERE e.topic = ti.topic
          AND e.event_type = ti.event_type
          AND e.window_end >= p.window_start - interval '10 minutes'
    );

    GET DIAGNOSTICS v_inserted = ROW_COUNT;
    PERFORM tgscan._log('event_detect', 'ok', format('inserted=%s', v_inserted));
    RETURN v_inserted;
EXCEPTION
    WHEN OTHERS THEN
        PERFORM tgscan._log('event_detect', 'fail', SQLERRM);
        RAISE;
END;
$$;

-- =====================================================================
-- Batch importance recalculation now targets bot.messages
-- =====================================================================
CREATE OR REPLACE FUNCTION tgscan.fn_recalc_importance(p_half_life_hours DOUBLE PRECISION DEFAULT 12)
RETURNS VOID LANGUAGE plpgsql AS $$
BEGIN
  PERFORM tgscan._log('importance','start',NULL);

  UPDATE bot.messages m
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
                 EXTRACT(EPOCH FROM (now() - m."date"))::double precision / 3600.0::double precision
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
  WHERE c.id = m.chat_id;

  PERFORM tgscan._log('importance','ok',NULL);
END;
$$;
