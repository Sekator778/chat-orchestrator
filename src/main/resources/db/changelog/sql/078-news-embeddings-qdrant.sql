-- =============================================================================
-- cs078: News embeddings + Qdrant spine (A1)
-- (1) embedded_at timestamptz on bot.messages — marks rows whose vector is in Qdrant
-- (2) app_settings knobs for the embedding job (all default-OFF)
-- =============================================================================

-- (1) embedded_at column on bot.messages
ALTER TABLE bot.messages
    ADD COLUMN IF NOT EXISTS embedded_at TIMESTAMPTZ;

-- sparse index: only the null rows need fast batch access
CREATE INDEX IF NOT EXISTS idx_messages_embedded_at_null
    ON bot.messages (id)
    WHERE embedded_at IS NULL;

-- (2) app_settings knobs (ON CONFLICT DO NOTHING — idempotent re-runs)
INSERT INTO bot.app_settings (name, value, value_type, description)
VALUES
    ('news.embedding.enabled',
     'false',
     'bool',
     'Master switch for the NewsEmbeddingScheduledJob. '
     'Set true on staging to activate; kept false in production until calibrated.'),

    ('news.embedding.batch-size',
     '200',
     'int',
     'How many news-eligible rows to embed per scheduled tick. '
     'Bounded to keep per-tick latency predictable and OpenAI cost controllable.'),

    ('news.embedding.interval-ms',
     '120000',
     'int',
     'Delay between embedding ticks (ms). Default 2 min. '
     'Tune lower once backfill of ~40k rows is observed to be progressing safely.')

ON CONFLICT (name) DO NOTHING;
