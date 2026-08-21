-- Stage 2: Events & Alerts foundational schema and rule-based detection
-- CONSOLIDATED: Includes all fixes and enhancements for clean database deployment
-- Original files: 017, 018, 019, 020, 021

-- ---------------------------------------------------------------------------
-- Utility Functions
-- ---------------------------------------------------------------------------

-- Logging function (from 018-fix-log-function)
CREATE OR REPLACE FUNCTION tgscan._log(
    p_step TEXT,
    p_status TEXT,
    p_details TEXT DEFAULT NULL
)
RETURNS VOID
LANGUAGE plpgsql
AS $$
BEGIN
    INSERT INTO tgscan.run_log (step, status, details)
    VALUES (p_step, p_status, p_details);
END;
$$;

-- ---------------------------------------------------------------------------
-- Tables
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS tgscan.events (
    id               BIGSERIAL PRIMARY KEY,
    event_type       TEXT        NOT NULL,
    topic            TEXT        NOT NULL,
    window_start     TIMESTAMPTZ NOT NULL,
    window_end       TIMESTAMPTZ NOT NULL,
    message_count    INTEGER     NOT NULL,
    unique_sources   INTEGER     NOT NULL,
    avg_importance   DOUBLE PRECISION,
    panic_ratio      DOUBLE PRECISION,
    spike_ratio      DOUBLE PRECISION,
    top_sources      JSONB,
    root_cause       TEXT,
    confidence       DOUBLE PRECISION,
    severity         TEXT,
    evidence         JSONB,
    created_at       TIMESTAMPTZ DEFAULT now(),
    updated_at       TIMESTAMPTZ DEFAULT now(),
    last_alert_at    TIMESTAMPTZ,
    rate_limit_key   TEXT,
    -- Status tracking (from 021)
    status           TEXT DEFAULT 'new' NOT NULL,
    processed_at     TIMESTAMPTZ,
    processing_error TEXT
);

COMMENT ON COLUMN tgscan.events.status IS 'Event processing status: new, ready, sent, suppressed, failed';
COMMENT ON COLUMN tgscan.events.processed_at IS 'Timestamp when event was processed by Event Watcher';
COMMENT ON COLUMN tgscan.events.processing_error IS 'Error message if processing failed';

CREATE TABLE IF NOT EXISTS tgscan.alerts (
    id              BIGSERIAL PRIMARY KEY,
    event_id        BIGINT REFERENCES tgscan.events(id) ON DELETE CASCADE,
    priority        TEXT,
    channel         TEXT,
    template        TEXT,
    rate_limit_key  TEXT,
    status          TEXT DEFAULT 'pending',
    delivered_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_tgscan_events_topic_window
    ON tgscan.events (topic, window_end DESC);
CREATE INDEX IF NOT EXISTS idx_tgscan_events_severity
    ON tgscan.events (severity, confidence DESC);
CREATE INDEX IF NOT EXISTS idx_tgscan_events_status_created
    ON tgscan.events (status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_tgscan_alerts_event_id
    ON tgscan.alerts (event_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_tgscan_alerts_event_id
    ON tgscan.alerts (event_id);

-- ---------------------------------------------------------------------------
-- Event detection function (with timezone fix from 019)
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION tgscan.fn_detect_events(
    p_window_minutes INT DEFAULT 15,
    p_min_confidence DOUBLE PRECISION DEFAULT 0.45
)
RETURNS INTEGER
LANGUAGE plpgsql
AS $$
DECLARE
    v_now TIMESTAMPTZ := now();  -- Fixed timezone handling
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
            m.msg_id,
            m.channel_id,
            c.username,
            m.posted_at,
            COALESCE(m.importance, 0.0) AS importance,
            COALESCE(NULLIF(m.text, ''), '(no text)') AS text,
            lower(kw) AS topic
        FROM tgscan.messages m
        JOIN params p
          ON m.posted_at >= p.window_start
         AND m.posted_at < p.window_end
        JOIN tgscan.channels c ON c.id = m.channel_id
        CROSS JOIN LATERAL UNNEST(COALESCE(m.matched_keywords, '{}')) kw
    ),
    topic_stats AS (
        SELECT
            topic,
            COUNT(*) AS message_count,
            COUNT(DISTINCT channel_id) AS unique_sources,
            AVG(importance) AS avg_importance,
            AVG(CASE WHEN importance <= 0.2 THEN 1 ELSE 0 END)::double precision AS panic_ratio
        FROM window_messages
        GROUP BY topic
    ),
    baseline_windows AS (
        SELECT
            lower(kw) AS topic,
            FLOOR(EXTRACT(EPOCH FROM (p.window_start - m.posted_at)) / (p_window_minutes * 60.0)) AS bucket,
            COUNT(*) AS window_count
        FROM params p, tgscan.messages m
        CROSS JOIN LATERAL UNNEST(COALESCE(m.matched_keywords, '{}')) kw
        WHERE m.posted_at >= (p.window_start - interval '2 hours')
          AND m.posted_at < p.window_start
        GROUP BY topic, bucket
    ),
    baseline_stats AS (
        SELECT
            topic,
            PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY window_count) AS median_baseline
        FROM baseline_windows
        GROUP BY topic
    ),
    scored_topics AS (
        SELECT
            t.topic,
            t.message_count,
            t.unique_sources,
            t.avg_importance,
            t.panic_ratio,
            COALESCE(b.median_baseline, 1.0) AS baseline,
            CASE
                WHEN COALESCE(b.median_baseline, 0) = 0 THEN 1.0
                ELSE GREATEST(t.message_count::double precision / NULLIF(b.median_baseline, 0), 1.0)
            END AS spike_ratio,
            0.3 * LEAST(t.message_count / 10.0, 1.0)
                + 0.3 * LEAST(t.unique_sources / 3.0, 1.0)
                + 0.2 * CASE WHEN COALESCE(b.median_baseline, 0) = 0 THEN 1.0
                             ELSE LEAST((t.message_count::double precision / NULLIF(b.median_baseline, 0) - 1.0) / 4.0, 1.0)
                        END
                + 0.2 * (1.0 - COALESCE(t.panic_ratio, 0.0)) AS confidence,
            (SELECT jsonb_agg(DISTINCT username) FROM window_messages wm WHERE wm.topic = t.topic) AS top_sources
        FROM topic_stats t
        LEFT JOIN baseline_stats b ON b.topic = t.topic
    ),
    candidates AS (
        SELECT
            'spike'::text AS event_type,
            topic,
            v_window_start AS window_start,
            v_now AS window_end,
            message_count::int,
            unique_sources::int,
            avg_importance,
            panic_ratio,
            spike_ratio,
            top_sources,
            NULL::text AS root_cause,
            confidence,
            CASE
                WHEN confidence >= 0.8 THEN 'critical'
                WHEN confidence >= 0.6 THEN 'high'
                WHEN confidence >= 0.45 THEN 'medium'
                ELSE 'low'
            END AS severity,
            jsonb_build_object(
                'baseline', baseline,
                'current', message_count
            ) AS evidence
        FROM scored_topics
        WHERE confidence >= p_min_confidence
    )
    INSERT INTO tgscan.events (
        event_type, topic, window_start, window_end, message_count,
        unique_sources, avg_importance, panic_ratio, spike_ratio,
        top_sources, root_cause, confidence, severity, evidence
    )
    SELECT
        event_type, topic, window_start, window_end, message_count,
        unique_sources, avg_importance, panic_ratio, spike_ratio,
        top_sources, root_cause, confidence, severity, evidence
    FROM candidates
    ON CONFLICT DO NOTHING
    RETURNING 1 INTO v_inserted;

    GET DIAGNOSTICS v_inserted = ROW_COUNT;

    PERFORM tgscan._log('event_detect', 'complete', format('inserted=%s', v_inserted));

    RETURN v_inserted;
END;
$$;

-- ---------------------------------------------------------------------------
-- Alert emission function (with format fix from 020)
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION tgscan.fn_emit_alerts()
RETURNS INTEGER
LANGUAGE plpgsql
AS $$
DECLARE
    v_emitted INTEGER := 0;
    v_event RECORD;
BEGIN
    PERFORM tgscan._log('alert_emit', 'start', NULL);

    FOR v_event IN
        SELECT
            e.id,
            e.topic,
            e.severity,
            e.message_count,
            e.unique_sources,
            e.confidence
        FROM tgscan.events e
        WHERE e.last_alert_at IS NULL
           OR e.last_alert_at < (now() - interval '1 hour')
        ORDER BY e.confidence DESC, e.created_at DESC
        LIMIT 10
    LOOP
        INSERT INTO tgscan.alerts (event_id, priority, channel, template, rate_limit_key, status)
        VALUES (
            v_event.id,
            v_event.severity,
            'default',
            format(
                'Событие: %s | Важность: %s | Сообщений: %s',  -- Fixed format specifier
                v_event.topic,
                v_event.severity,
                v_event.message_count::text
            ),
            format('topic:%s', v_event.topic),
            'pending'
        )
        ON CONFLICT (event_id) DO NOTHING;

        IF FOUND THEN
            v_emitted := v_emitted + 1;

            UPDATE tgscan.events
            SET last_alert_at = now(),
                updated_at = now()
            WHERE id = v_event.id;
        END IF;
    END LOOP;

    PERFORM tgscan._log('alert_emit', 'complete', format('emitted=%s', v_emitted));

    RETURN v_emitted;
END;
$$;
