-- Test 1: Свежий пост с высоким engagement должен быть топ-1
-- Вставляем два поста: свежий с высоким engagement и старый с умеренным.
-- Assert: свежий имеет importance > старого.
-- Метод: прямое вычисление по формуле триггера (без реальной вставки в bot.messages,
--         чтобы не создавать шума в данных).

BEGIN;

CREATE TEMP TABLE t_scoring_test_01 (
    label       TEXT,
    weight      DOUBLE PRECISION,
    consensus   DOUBLE PRECISION,
    novelty     DOUBLE PRECISION,
    views       BIGINT,
    forwards    BIGINT,
    subscribers DOUBLE PRECISION,
    age_hours   DOUBLE PRECISION
) ON COMMIT DROP;

INSERT INTO t_scoring_test_01 VALUES
    -- свежий пост, высокий engagement
    ('fresh_high',   0.8, 0.9, 0.9, 50000, 800,  100000, 1.0),
    -- умеренный пост, хороший engagement, но старый
    ('old_moderate', 0.8, 0.9, 0.9, 60000, 1000, 100000, 48.0);

WITH scored AS (
    SELECT
        label,
        age_hours,
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
    FROM t_scoring_test_01
),
result AS (
    SELECT
        MAX(CASE WHEN label = 'fresh_high'   THEN importance END) AS fresh_score,
        MAX(CASE WHEN label = 'old_moderate' THEN importance END) AS old_score
    FROM scored
)
SELECT
    fresh_score,
    old_score,
    CASE
        WHEN fresh_score > old_score THEN 'PASS: Свежий пост с высоким engagement > старого'
        ELSE 'FAIL: Ожидалось fresh_score > old_score, получили ' ||
             ROUND(fresh_score::numeric, 4) || ' vs ' || ROUND(old_score::numeric, 4)
    END AS test_result
FROM result;

ROLLBACK;
