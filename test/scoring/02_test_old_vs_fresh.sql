-- Test 2: Старый пост (48h) должен проиграть свежему даже при лучшем engagement
-- Свежий пост (1h) с умеренным engagement vs старый (48h) с более высоким.
-- Assert: свежий пост ранжируется выше.

BEGIN;

CREATE TEMP TABLE t_scoring_test_02 (
    label       TEXT,
    weight      DOUBLE PRECISION,
    consensus   DOUBLE PRECISION,
    novelty     DOUBLE PRECISION,
    views       BIGINT,
    forwards    BIGINT,
    subscribers DOUBLE PRECISION,
    age_hours   DOUBLE PRECISION
) ON COMMIT DROP;

INSERT INTO t_scoring_test_02 VALUES
    -- свежий пост (1h), умеренный engagement
    ('fresh_1h',  0.6, 0.5, 0.5, 5000,  100, 50000, 1.0),
    -- старый пост (48h), более высокий engagement
    ('old_48h',   0.6, 0.5, 0.5, 15000, 400, 50000, 48.0);

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
    FROM t_scoring_test_02
),
result AS (
    SELECT
        MAX(CASE WHEN label = 'fresh_1h' THEN importance END) AS fresh_score,
        MAX(CASE WHEN label = 'old_48h'  THEN importance END) AS old_score
    FROM scored
)
SELECT
    ROUND(fresh_score::numeric, 4) AS fresh_1h_score,
    ROUND(old_score::numeric, 4)   AS old_48h_score,
    CASE
        WHEN fresh_score > old_score THEN 'PASS: Свежий пост (1h) > старого (48h)'
        ELSE 'FAIL: Ожидалось fresh_score > old_score, получили ' ||
             ROUND(fresh_score::numeric, 4) || ' vs ' || ROUND(old_score::numeric, 4)
    END AS test_result
FROM result;

-- Дополнительно: показать вклад time decay при 1h и 48h
SELECT
    'age=1h'  AS scenario, ROUND(EXP(-LN(2.0) * 1.0  / 24.0)::numeric, 4) AS decay_factor,
    ROUND((0.15 * EXP(-LN(2.0) * 1.0  / 24.0))::numeric, 4) AS decay_component
UNION ALL SELECT
    'age=48h' AS scenario, ROUND(EXP(-LN(2.0) * 48.0 / 24.0)::numeric, 4) AS decay_factor,
    ROUND((0.15 * EXP(-LN(2.0) * 48.0 / 24.0))::numeric, 4) AS decay_component;

ROLLBACK;
