-- Stage 3: Event Publishing - Subscriptions and delivery tracking

-- ============================================================================
-- Post Subscriptions: Who gets what notifications
-- ============================================================================
CREATE TABLE IF NOT EXISTS tgscan.post_subscriptions (
    id              BIGSERIAL PRIMARY KEY,
    chat_id         BIGINT NOT NULL,                  -- Telegram chat/channel ID (can be negative)
    enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    topic_pattern   TEXT NOT NULL,                    -- Regex pattern: '^btc$' or 'eth|btc'
    event_types     TEXT[] NOT NULL DEFAULT ARRAY['SPIKE','FUD/PANIC','FOMO/LISTING'],
    min_severity    TEXT NOT NULL DEFAULT 'low',      -- 'low' | 'medium' | 'high'
    template_code   TEXT NOT NULL DEFAULT 'RICH',     -- 'RICH' | 'SHORT'
    dedupe_ttl_sec  INT NOT NULL DEFAULT 1200,        -- 20 minutes: don't duplicate same topic/type
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (chat_id, topic_pattern, template_code)
);

CREATE INDEX IF NOT EXISTS idx_post_subscriptions_enabled_pattern
    ON tgscan.post_subscriptions (enabled, topic_pattern) WHERE enabled = TRUE;

COMMENT ON TABLE tgscan.post_subscriptions IS 'Defines who receives event notifications and in what format';
COMMENT ON COLUMN tgscan.post_subscriptions.topic_pattern IS 'PostgreSQL regex pattern for topic matching (case-insensitive)';
COMMENT ON COLUMN tgscan.post_subscriptions.event_types IS 'Array of event types to include: SPIKE, FUD/PANIC, FOMO/LISTING';
COMMENT ON COLUMN tgscan.post_subscriptions.template_code IS 'Template to use: RICH (detailed) or SHORT (compact)';
COMMENT ON COLUMN tgscan.post_subscriptions.dedupe_ttl_sec IS 'Time window to prevent duplicate posts for same event type/topic';

-- ============================================================================
-- Posted Events: Audit trail and idempotency
-- ============================================================================
CREATE TABLE IF NOT EXISTS tgscan.posted (
    id                  BIGSERIAL PRIMARY KEY,
    event_id            BIGINT NOT NULL REFERENCES tgscan.events(id) ON DELETE CASCADE,
    subscription_id     BIGINT NOT NULL REFERENCES tgscan.post_subscriptions(id) ON DELETE CASCADE,
    chat_id             BIGINT NOT NULL,
    message_id          BIGINT,                       -- Telegram message ID if successful
    template_code       TEXT NOT NULL,
    status              TEXT NOT NULL DEFAULT 'sent', -- 'sent' | 'failed'
    error_message       TEXT,
    posted_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (event_id, subscription_id)                -- Idempotency: one event → one post per subscription
);

CREATE INDEX IF NOT EXISTS idx_posted_event_id ON tgscan.posted (event_id);
CREATE INDEX IF NOT EXISTS idx_posted_chat_posted_at ON tgscan.posted (chat_id, posted_at DESC);
CREATE INDEX IF NOT EXISTS idx_posted_status ON tgscan.posted (status, posted_at DESC);

COMMENT ON TABLE tgscan.posted IS 'Audit trail of published events for analytics and idempotency';
COMMENT ON COLUMN tgscan.posted.message_id IS 'Telegram message ID returned by sendMessage API';

-- ============================================================================
-- Helper function: Severity ranking for comparisons
-- ============================================================================
CREATE OR REPLACE FUNCTION tgscan.severity_rank(severity TEXT)
RETURNS INT
IMMUTABLE
LANGUAGE plpgsql
AS $$
BEGIN
    RETURN CASE lower(severity)
        WHEN 'low' THEN 1
        WHEN 'medium' THEN 2
        WHEN 'high' THEN 3
        ELSE 0
    END;
END;
$$;

COMMENT ON FUNCTION tgscan.severity_rank IS 'Convert severity text to numeric rank for filtering (low=1, medium=2, high=3)';
