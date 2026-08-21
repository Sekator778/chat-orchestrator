-- Test 4: views=0 → вклад engagement = 0, без ошибки деления на ноль
-- Проверяем все комбинации граничных значений: views=0, subscribers=NULL, subscribers=0.

BEGIN;

CREATE TEMP TABLE t_scoring_test_04 (
    label       TEXT,
    weight      DOUBLE PRECISION,
    views       BIGINT,
    forwards    BIGINT,
    subscribers DOUBLE PRECISION,
    age_hours   DOUBLE PRECISION
) ON COMMIT DROP;

INSERT INTO t_scoring_test_04 VALUES
    ('views=0, subs=50000',  0.5, 0,    0,   50000, 1.0),  -- views=0
    ('views=0, subs=NULL',   0.5, 0,    0,   NULL,  1.0),  -- views=0, subs=NULL
    ('views=0, subs=0',      0.5, 0,    0,   0,     1.0),  -- деление на ноль защищено
    ('views=1, subs=0',      0.5, 1,    0,   0,     1.0),  -- views>0, subs=0 → penetration=0
    ('views=NULL, subs=100', 0.5, NULL, NULL, 100,  1.0);  -- NULL views

WITH scored AS (
    SELECT
        label,
        views, subscribers,
        LEAST(1.0, GREATEST(1e-12, (
            0.35 * COALESCE(weight, 0.0)
            + 0.25 * 0.5   -- consensus/novelty default
            + 0.20 * (
                0.6 * tgscan._norm_log(COALESCE(views,0), 10000)
                    * LEAST(1.0, CASE WHEN subscribers IS NULL OR subscribers <= 0 THEN 0.0
                                      ELSE COALESCE(views,0)::double precision / GREATEST(1.0, subscribers) END)
                + 0.4 * tgscan._norm_log(COALESCE(forwards,0), 300)
            )
            + 0.15 * EXP(-LEAST(700.0, LN(2.0) * age_hours / 24.0))
        ))) AS importance,
        0.20 * (
            0.6 * tgscan._norm_log(COALESCE(views,0), 10000)
                * LEAST(1.0, CASE WHEN subscribers IS NULL OR subscribers <= 0 THEN 0.0
                                  ELSE COALESCE(views,0)::double precision / GREATEST(1.0, subscribers) END)
            + 0.4 * tgscan._norm_log(COALESCE(forwards,0), 300)
        ) AS engagement_component
    FROM t_scoring_test_04
)
SELECT
    label,
    views,
    subscribers,
    ROUND(engagement_component::numeric, 6) AS engagement_component,
    ROUND(importance::numeric, 4)           AS importance,
    CASE
        WHEN importance IS NOT NULL AND importance > 0 THEN 'PASS: Нет ошибки, importance > 0'
        ELSE 'FAIL: importance = ' || COALESCE(importance::text, 'NULL')
    END AS test_result
FROM scored;

-- Ключевой assert: для всех строк engagement_component должен быть = 0 (кроме NULL views)
SELECT
    CASE
        WHEN COUNT(*) FILTER (WHERE engagement_component > 1e-12) = 0
            THEN 'PASS: Все случаи views=0/NULL дают engagement_component = 0'
        ELSE 'FAIL: Некоторые случаи дали ненулевой engagement при views=0'
    END AS final_assert
FROM (
    SELECT 0.20 * (
        0.6 * tgscan._norm_log(COALESCE(views,0), 10000)
            * LEAST(1.0, CASE WHEN subscribers IS NULL OR subscribers <= 0 THEN 0.0
                              ELSE COALESCE(views,0)::double precision / GREATEST(1.0, subscribers) END)
        + 0.4 * tgscan._norm_log(COALESCE(forwards,0), 300)
    ) AS engagement_component
    FROM t_scoring_test_04
    WHERE views = 0 OR views IS NULL
) sub;

ROLLBACK;
