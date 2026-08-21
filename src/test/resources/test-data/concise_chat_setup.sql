-- =============================================================================
-- Test data setup for CONCISE response style testing
-- Creates test chat configuration with SimpleLlmService generation
-- =============================================================================

-- Test Chat 1: CONCISE style with default settings
INSERT INTO bot.chat_configs (id, channel_chat_id, prompt_template, enabled, max_tokens, temperature,
                               language, context_window_size, primary_channel_id, default_sync_depth_days,
                               auto_sync_enabled, sync_enabled, respond_to_forwarded_bot_messages)
VALUES (
	           1001,
	           -1001001,
	           'Short human reactions only',
	           true,
	           95,
	           0.4,
	           'ru',
	           6,
	           NULL,
           7,
           false,
           false,
	           false
	       ) ON CONFLICT (id) DO NOTHING;

INSERT INTO bot.rate_limits (chat_config_id, max_messages_per_day, current_daily_messages)
VALUES (1001, 30, 0)
ON CONFLICT (chat_config_id) DO UPDATE SET
    max_messages_per_day = EXCLUDED.max_messages_per_day,
    current_daily_messages = EXCLUDED.current_daily_messages;

-- CONCISE response template for test chat 1
INSERT INTO bot.response_templates (id, chat_config_id, template_name, template_content,
                                     response_style, response_tone, max_response_length,
                                     is_default, priority, active)
VALUES (
           2001,
           1001,
           'test_concise_default',
           'Короткая реакция',
           'CONCISE',
           'NEUTRAL',
           160,
           true,
           1,
           true
       ) ON CONFLICT (id) DO NOTHING;

-- Test Chat 2: CONCISE style with very short limit
INSERT INTO bot.chat_configs (id, channel_chat_id, prompt_template, enabled, max_tokens, temperature,
                               language, context_window_size, primary_channel_id, default_sync_depth_days,
                               auto_sync_enabled, sync_enabled, respond_to_forwarded_bot_messages)
VALUES (
	           1002,
	           -1001002,
	           'Ultra short reactions',
	           true,
	           50,
	           0.9,
	           'ru',
	           3,
	           NULL,
           7,
           false,
           false,
	           false
	       ) ON CONFLICT (id) DO NOTHING;

INSERT INTO bot.rate_limits (chat_config_id, max_messages_per_day, current_daily_messages)
VALUES (1002, 50, 0)
ON CONFLICT (chat_config_id) DO UPDATE SET
    max_messages_per_day = EXCLUDED.max_messages_per_day,
    current_daily_messages = EXCLUDED.current_daily_messages;

-- Very short CONCISE template
INSERT INTO bot.response_templates (id, chat_config_id, template_name, template_content,
                                     response_style, response_tone, max_response_length,
                                     is_default, priority, active)
VALUES (
           2002,
           1002,
           'test_concise_short',
           'Очень короткая реакция',
           'CONCISE',
           'CASUAL',
           50,
           true,
           1,
           true
       ) ON CONFLICT (id) DO NOTHING;

-- Test Chat 3: ADAPTIVE style for comparison
INSERT INTO bot.chat_configs (id, channel_chat_id, prompt_template, enabled, max_tokens, temperature,
                               language, context_window_size, primary_channel_id, default_sync_depth_days,
                               auto_sync_enabled, sync_enabled, respond_to_forwarded_bot_messages)
VALUES (
	           1003,
	           -1001003,
	           'Normal multi-stage processing',
	           true,
	           1000,
	           0.7,
	           'ru',
	           15,
	           NULL,
           7,
           false,
           false,
	           false
	       ) ON CONFLICT (id) DO NOTHING;

INSERT INTO bot.rate_limits (chat_config_id, max_messages_per_day, current_daily_messages)
VALUES (1003, 100, 0)
ON CONFLICT (chat_config_id) DO UPDATE SET
    max_messages_per_day = EXCLUDED.max_messages_per_day,
    current_daily_messages = EXCLUDED.current_daily_messages;

-- ADAPTIVE response template (uses standard flow)
INSERT INTO bot.response_templates (id, chat_config_id, template_name, template_content,
                                     response_style, response_tone, max_response_length,
                                     is_default, priority, active)
VALUES (
           2003,
           1003,
           'test_adaptive_default',
           'Standard processing',
           'ADAPTIVE',
           'NEUTRAL',
           500,
           true,
           1,
           true
       ) ON CONFLICT (id) DO NOTHING;

-- Test Chat 4: CONCISE with FRIENDLY tone
INSERT INTO bot.chat_configs (id, channel_chat_id, prompt_template, enabled, max_tokens, temperature,
                               language, context_window_size, primary_channel_id, default_sync_depth_days,
                               auto_sync_enabled, sync_enabled, respond_to_forwarded_bot_messages)
VALUES (
	           1004,
	           -1001004,
	           'Friendly short reactions',
	           true,
	           100,
	           0.85,
	           'ru',
	           5,
	           NULL,
           7,
           false,
           false,
	           false
	       ) ON CONFLICT (id) DO NOTHING;

INSERT INTO bot.rate_limits (chat_config_id, max_messages_per_day, current_daily_messages)
VALUES (1004, 40, 0)
ON CONFLICT (chat_config_id) DO UPDATE SET
    max_messages_per_day = EXCLUDED.max_messages_per_day,
    current_daily_messages = EXCLUDED.current_daily_messages;

-- CONCISE with FRIENDLY tone
INSERT INTO bot.response_templates (id, chat_config_id, template_name, template_content,
                                     response_style, response_tone, max_response_length,
                                     is_default, priority, active)
VALUES (
           2004,
           1004,
           'test_concise_friendly',
           'Дружелюбная короткая реакция',
           'CONCISE',
           'FRIENDLY',
           120,
           true,
           1,
           true
       ) ON CONFLICT (id) DO NOTHING;

-- Sample test messages for chat 1 (CONCISE)
INSERT INTO bot.messages (id, chat_id, message_id, content, date, is_outgoing, message_type, created_at, sender_id)
VALUES
    (10001, -1001001, 1, 'Акции Apple упали на 5%', NOW() - INTERVAL '5 minutes', false, 'USER_MESSAGE', NOW(), 111111),
    (10002, -1001001, 2, 'В Украине подорожал хлеб', NOW() - INTERVAL '4 minutes', false, 'USER_MESSAGE', NOW(), 222222),
    (10003, -1001001, 3, 'Tesla анонсировала новый аккумулятор', NOW() - INTERVAL '3 minutes', false, 'USER_MESSAGE', NOW(), 111111)
ON CONFLICT (chat_id, message_id) DO NOTHING;

-- Sample test messages for chat 2 (CONCISE short)
INSERT INTO bot.messages (id, chat_id, message_id, content, date, is_outgoing, message_type, created_at, sender_id)
VALUES
    (10011, -1001002, 1, 'Дождь всю неделю', NOW() - INTERVAL '2 minutes', false, 'USER_MESSAGE', NOW(), 333333),
    (10012, -1001002, 2, 'Завтра экзамен', NOW() - INTERVAL '1 minute', false, 'USER_MESSAGE', NOW(), 444444)
ON CONFLICT (chat_id, message_id) DO NOTHING;

-- Sample test messages for chat 3 (ADAPTIVE)
INSERT INTO bot.messages (id, chat_id, message_id, content, date, is_outgoing, message_type, created_at, sender_id)
VALUES
    (10021, -1001003, 1, 'Какие перспективы у криптовалюты в 2025?', NOW() - INTERVAL '10 minutes', false, 'USER_MESSAGE', NOW(), 555555)
ON CONFLICT (chat_id, message_id) DO NOTHING;

-- Channels in tgscan schema
INSERT INTO tgscan.channels (id, title, join_status, joined_at, last_seen, is_channel)
VALUES
    (-1001001, 'Test CONCISE Chat', 'joined', NOW(), NOW(), false),
    (-1001002, 'Test CONCISE Short Chat', 'joined', NOW(), NOW(), false),
    (-1001003, 'Test ADAPTIVE Chat', 'joined', NOW(), NOW(), false),
    (-1001004, 'Test CONCISE Friendly Chat', 'joined', NOW(), NOW(), false)
ON CONFLICT (id) DO NOTHING;
