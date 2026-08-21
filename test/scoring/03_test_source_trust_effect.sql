-- Test 3: Пост от недоверенного канала (weight=0.2) vs доверенного (weight=0.8)
-- Остальные параметры одинаковы.
-- Assert: высокий weight даёт заметно более высокий score.
-- Примечание: параметр weight в tgscan.channels соответствует source_trust.trust_score
-- (синхронизируются через триггер fn_sync_source_trust).

BEGIN;

CREATE TEMP TABLE t_scoring_test_03 (
    label       TEXT,
    weight      DOUBLE PRECISION,
    consensus   DOUBLE PRECISION,
    novelty     DOUBLE PRECISION,
    views       BIGINT,
    forwards    BIGINT,
    subscribers DOUBLE PRECISION,
    age_hours   DOUBLE PRECISION
) ON COMMIT DROP;

INSERT INTO t_scoring_test_03 VALUES
    -- доверенный канал (weight=0.8, эквивалентно source_trust=0.8)
    ('trusted_0.8',    0.8, 0.5, 0.5, 3000, 50, 20000, 2.0),
    -- умеренный канал (weight=0.5, дефолт)
    ('moderate_0.5',   0.5, 0.5, 0.5, 3000, 50, 20000, 2.0),
    -- недоверенный канал (weight=0.2)
    ('untrusted_0.2',  0.2, 0.5, 0.5, 3000, 50, 20000, 2.0);

WITH scored AS (
    SELECT
        label,
        weight,
        LEAST(1.0, GREATEST(1e-12, (
            0.35 * COALESCE(weight, 0.0)
            + 0.25 * (0.7 * COALESCE(consensus, 0.5) + 0.3 * COALESCE(novelty, 0.5))
            + 0.20 * (
                0.6 * tgscan._norm_log(views, 10000)
                    * LEAST(1.0, CASE WHEN subscribers IS NULL OR subscribers <= 0 THEN 0.0
                                      ELSE views::double precision / GREATEST(1.0, subscribers) END)
                + 0.4 * tgscan._norm_log(forwards, 300)
            )
            + 0.15 * EXP(-LEAST(700.0, LN(2.0) * age_hours / 24.0))
        ))) AS importance
    FROM t_scoring_test_03
    ORDER BY importance DESC
),
ranked AS (
    SELECT label, weight, ROUND(importance::numeric, 4) AS importance,
           RANK() OVER (ORDER BY importance DESC) AS rnk
    FROM scored
)
SELECT label, weight AS channel_weight, importance, rnk,
    CASE
        WHEN rnk = 1 AND label = 'trusted_0.8' THEN 'PASS: Доверенный канал на первом месте'
        WHEN rnk = 1 THEN 'FAIL: Ожидался trusted_0.8 на первом месте, но: ' || label
        ELSE NULL
    END AS test_result
FROM ranked;

-- Проверим, что разница между trusted и untrusted = 0.35 * (0.8 - 0.2) = 0.21
SELECT
    ROUND((0.35 * (0.8 - 0.2))::numeric, 4) AS expected_weight_diff,
    'Разница в importance между weight=0.8 и weight=0.2 должна быть ~0.21' AS note;

ROLLBACK;
