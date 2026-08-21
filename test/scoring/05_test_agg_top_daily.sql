-- Test 5: agg_top_messages_daily содержит ожидаемые топ-5 за последние 24h
-- Вставляем 7 тестовых сообщений в tgscan.messages с известными importance-значениями,
-- вызываем fn_build_agg_top_daily, проверяем топ-5, затем rollback.
--
-- ВАЖНО: fn_build_agg_top_daily выполняет DELETE + INSERT без транзакционной защиты
-- внутри функции. Поэтому тест оборачивает всё в BEGIN...ROLLBACK.

BEGIN;

-- Шаг 1: Сохранить текущее состояние agg_top_messages_daily
CREATE TEMP TABLE t_agg_backup AS
    SELECT * FROM tgscan.agg_top_messages_daily;

-- Шаг 2: Вставить тестовый канал (если нет)
INSERT INTO tgscan.channels (id, username, title, weight, subscribers)
VALUES (-999999001, 'test_audit_ch', 'Test Audit Channel', 0.7, 100000)
ON CONFLICT (id) DO UPDATE SET weight = EXCLUDED.weight;

-- Шаг 3: Вставить тестовые сообщения с известной importance
-- (posted_at в пределах последних 24 часов)
INSERT INTO tgscan.messages (msg_id, channel_id, posted_at, text, views, forwards, importance)
VALUES
    (9000001, -999999001, now() - interval '1 hour',  'Top 1 post — max importance',  90000, 2000, 0.92),
    (9000002, -999999001, now() - interval '2 hours', 'Top 2 post',                   70000, 1500, 0.85),
    (9000003, -999999001, now() - interval '3 hours', 'Top 3 post',                   50000, 1000, 0.78),
    (9000004, -999999001, now() - interval '4 hours', 'Top 4 post',                   30000,  700, 0.68),
    (9000005, -999999001, now() - interval '5 hours', 'Top 5 post',                   20000,  500, 0.60),
    (9000006, -999999001, now() - interval '6 hours', 'Low importance post 1',         5000,   50, 0.25),
    (9000007, -999999001, now() - interval '7 hours', 'Low importance post 2',         1000,   10, 0.10)
ON CONFLICT DO NOTHING;

-- Шаг 4: Запустить fn_build_agg_top_daily
PERFORM tgscan.fn_build_agg_top_daily(500);

-- Шаг 5: Проверить результаты
WITH top_results AS (
    SELECT
        msg_id,
        importance,
        RANK() OVER (ORDER BY importance DESC NULLS LAST) AS rnk
    FROM tgscan.agg_top_messages_daily
    WHERE channel_id = -999999001
)
SELECT
    msg_id,
    ROUND(importance::numeric, 2) AS importance,
    rnk,
    CASE
        WHEN rnk = 1 AND msg_id = 9000001 THEN 'PASS: msg 9000001 на позиции 1'
        WHEN rnk = 1 THEN 'FAIL: Ожидался msg 9000001 на позиции 1, получили msg ' || msg_id
        ELSE NULL
    END AS rank_1_assert
FROM top_results
ORDER BY rnk;

-- Шаг 6: Проверить что топ-5 содержит именно ожидаемые msg_id
WITH test_msgs AS (
    SELECT msg_id, RANK() OVER (ORDER BY importance DESC NULLS LAST) AS rnk
    FROM tgscan.agg_top_messages_daily
    WHERE channel_id = -999999001
),
expected_top5 AS (
    SELECT UNNEST(ARRAY[9000001, 9000002, 9000003, 9000004, 9000005]) AS expected_id
),
missing AS (
    SELECT e.expected_id
    FROM expected_top5 e
    LEFT JOIN test_msgs t ON t.msg_id = e.expected_id AND t.rnk <= 5
    WHERE t.msg_id IS NULL
)
SELECT
    CASE
        WHEN COUNT(*) = 0 THEN 'PASS: Все 5 ожидаемых сообщений присутствуют в топ-5'
        ELSE 'FAIL: Не найдены в топ-5: ' || STRING_AGG(expected_id::text, ', ')
    END AS final_assert
FROM missing;

-- Шаг 7: Проверить что низковажные посты (9000006, 9000007) не в топ-5
WITH test_msgs AS (
    SELECT msg_id, RANK() OVER (ORDER BY importance DESC NULLS LAST) AS rnk
    FROM tgscan.agg_top_messages_daily
    WHERE channel_id = -999999001
)
SELECT
    CASE
        WHEN COUNT(*) FILTER (WHERE msg_id IN (9000006, 9000007) AND rnk <= 5) = 0
            THEN 'PASS: Низковажные посты не попали в топ-5'
        ELSE 'FAIL: Низковажный пост попал в топ-5'
    END AS low_importance_assert
FROM test_msgs;

-- Шаг 8: Восстановить agg_top_messages_daily (будет отменено ROLLBACK, но явно чистим)
-- ROLLBACK ниже откатит все изменения включая DELETE в fn_build_agg_top_daily

ROLLBACK;

-- После ROLLBACK состояние agg_top_messages_daily восстановлено
SELECT COUNT(*) AS rows_after_rollback,
       'Таблица восстановлена после rollback' AS note
FROM tgscan.agg_top_messages_daily;
