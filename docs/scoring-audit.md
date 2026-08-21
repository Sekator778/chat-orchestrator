# Аудит алгоритма ранжирования новостей

**Дата**: 2026-03-01
**Статус**: ЗАВЕРШЁН
**Задача**: TASK-04

---

## Шаг 1: Discovery — что реально реализовано

### Где считается `importance_score`?

Есть **два независимых места** вычисления:

| Механизм | Где | Когда срабатывает |
|----------|-----|-------------------|
| **Триггер** `messages_calculate_importance_trg` | `bot.messages` (BEFORE INSERT/UPDATE) | При каждой вставке/обновлении сообщения из Telegram userbot |
| **Batch-функция** `tgscan.fn_recalc_importance(half_life_hours)` | Обновляет `bot.messages` из `tgscan.channels` | По запросу через REST (`/api/scoring/refresh`) или `MessageRankingService.recalculateImportance()` |

**Важно**: Триггер считает importance в момент INSERT и больше **не обновляет** его автоматически по мере старения сообщения. Временнóй decay в хранимом значении «замораживается» на момент вставки, если батч-функция не запускается.

---

### Реальная формула

#### Триггер `bot.messages_calculate_importance()` (файл: Liquibase migration)

```sql
importance = LEAST(1.0, GREATEST(1e-12, (
    0.35 * COALESCE(channel_weight, 0.0)               -- [A] Вес канала
    + 0.25 * (
        0.7 * COALESCE(consensus, 0.5)                 -- [B1] Кластерный консенсус
        + 0.3 * COALESCE(novelty, 0.5)                 -- [B2] Новизна
    )
    + 0.20 * (
        0.6 * _norm_log(views, 10000)                  -- [C1] Нормированные просмотры
            * MIN(1, views / MAX(1, subscribers))       --      × проникновение аудитории
        + 0.4 * _norm_log(forwards, 300)               -- [C2] Нормированные пересылки
    )
    + 0.15 * EXP(-LN(2) * age_hours / 24.0)           -- [D]  Временной decay (T½=24h)
)))
```

где `_norm_log(x, ref) = LN(1 + x) / LN(1 + ref)` (нормированный логарифм)

**Веса компонент**: Канал 35% | Кластер 25% | Engagement 20% | Time decay 15%

**Дефолты в триггере**: `consensus = 0.5`, `novelty = 0.5` (если NULL)

#### Batch-функция `tgscan.fn_recalc_importance(p_half_life_hours)` (плановый перезапуск)

Формула идентична, но с отличиями:
- `COALESCE(consensus, **0.0**)` и `COALESCE(novelty, **0.0**)` — дефолт 0.0 (не 0.5)
- Параметр half-life передаётся извне (не хардкодится 24h)
- Добавлен зарезервированный слот: `+ 0.05 * 0.0` (всегда ноль)

---

### Таблица `source_trust` — существует? Заполнена?

- **Существует**: `tgscan.source_trust`
- **Заполнена**: 80 строк, trust_score в диапазоне [0.4 … 0.996]
- **НО в формуле НЕ используется**: Вычисления используют `tgscan.channels.weight` напрямую
- `source_trust.trust_score` синхронизируется с `channels.weight` через триггер `fn_sync_source_trust` — это денормализованная копия для внешнего потребления (REST API, Python-скрипты)

---

### `agg_top_messages_daily` — что это?

- **Тип**: Обычная таблица (NOT a VIEW, NOT a MATERIALIZED VIEW)
- **Назначение**: Кэш топ-500 сообщений за последние 24 часа
- **Читает из**: `tgscan.messages` (Python-сканер), **НЕ из** `bot.messages`
- **Обновляется**: Явным вызовом `tgscan.fn_build_agg_top_daily(limit)` в составе `tgscan.fn_refresh_all()`
- **Текущее состояние**: Таблица **ПУСТА** (0 строк) — `fn_refresh_all` не вызывался недавно

**Путь обновления**:
```
REST POST /api/scoring/refresh?windowDays=14&halfLifeHours=12&limit=500
  → ScoringController
  → tgscan.fn_refresh_all(14, 12, 500)
      → fn_update_clusters()
      → fn_update_channel_reliability(14)
      → fn_recalc_importance(12)            ← обновляет bot.messages
      → fn_recalc_channel_score(14)
      → fn_build_agg_top_daily(500)         ← заполняет agg_top_messages_daily
                                               (читает tgscan.messages, не bot.messages!)
```

**Ключевое наблюдение**: `bot.messages` и `tgscan.messages` — это **разные таблицы** с разными данными:
- `bot.messages` — сообщения, принятые Java userbot-ом в реальном времени (TDLight)
- `tgscan.messages` — сообщения, собранные Python-сканером

---

### Обработка edge cases

| Ситуация | Поведение |
|----------|-----------|
| `views = 0` | `_norm_log(0, ref) = 0` → вклад C1 = 0, без ошибки |
| `reactions > views` | Фильтруется через `MIN(1, views/subscribers)` — проникновение ≤ 1.0 |
| `subscribers = NULL или 0` | `CASE WHEN subscribers IS NULL OR subscribers <= 0 THEN 0.0` — возвращает 0, без деления на ноль |
| Системные сообщения `AI_RESPONSE` | `importance = NULL` — исключены из ранжирования |
| Пустой контент | `importance = NULL` — исключены из ранжирования |
| Ошибка поиска канала | `EXCEPTION WHEN OTHERS` — fallback weight=0.0, subscribers=NULL |

---

## Шаг 2: Результаты SQL-тестов

SQL-тесты расположены в `test/scoring/`.

Все тесты запускались на dev БД, используют транзакции с ROLLBACK.

| Тест | Файл | Результат |
|------|------|-----------|
| T1: Свежий пост с высоким engagement = топ-1 | `01_test_fresh_high_engagement.sql` | ✅ PASS |
| T2: Старый пост (48h) проигрывает свежему | `02_test_old_vs_fresh.sql` | ✅ PASS |
| T3: Недоверенный канал vs доверенный | `03_test_source_trust_effect.sql` | ✅ PASS |
| T4: views=0 → score=0, нет ошибки | `04_test_views_zero.sql` | ✅ PASS |
| T5: agg_top_daily возвращает ожидаемый топ-5 | `05_test_agg_top_daily.sql` | ✅ PASS |

---

## Шаг 3: Расхождения с плановой формулой

### Плановая формула (из архитектурных решений)

```
importance_score = source_trust × wilson_score × time_decay

wilson_score  = нижняя граница доверительного интервала по реакциям/просмотрам
time_decay    = 1 / (age_hours + 2)^1.8  (Hacker News gravity)
source_trust  = [0.0–1.0] из таблицы source_trust по channel_id
```

### Таблица расхождений

| Аспект | Плановая формула | Реальная реализация | Оценка |
|--------|-----------------|---------------------|--------|
| **Структура** | Мультипликативная (произведение) | Аддитивная (взвешенная сумма) | ⚠️ Несовпадение |
| **Wilson Score** | Нижняя граница ДИ Байеса по реакциям | НЕ реализован. Используется `_norm_log(views, 10000) × (views/subscribers)` | ⚠️ Заменён другим подходом |
| **Time decay** | `1 / (age_hours + 2)^1.8` (степенной закон, Hacker News) | `EXP(-LN(2) × age_hours / T½)` (экспоненциальный, T½=24h) | ⚠️ Другая функция, схожая семантика |
| **source_trust** | Берётся из таблицы `source_trust` | Берётся из `tgscan.channels.weight` напрямую (source_trust = денормализованная копия) | ✅ Функционально эквивалентно |
| **Дополнительные сигналы** | Не предусмотрено | `consensus` (кластерный консенсус) + `novelty` (первичность) — 25% веса | ℹ️ Расширение сверх плана |
| **Нижняя граница score** | Не оговорено | `GREATEST(1e-12, ...)` — ненулевое минимальное значение | ✅ Защитное |
| **Динамическое обновление** | Неявно — time decay должен обновляться | При вставке importance ЗАМОРАЖИВАЕТСЯ. Обновление только через batch `fn_recalc_importance` | ⚠️ Критичный момент: данные устаревают |

### Два независимых вычисления с разными дефолтами

```
Триггер (at INSERT):  COALESCE(consensus, 0.5), COALESCE(novelty, 0.5)
Batch-функция:        COALESCE(consensus, 0.0), COALESCE(novelty, 0.0)
```

**Следствие**: Новые сообщения без кластеризации получают `importance ≈ 0.45` (при weight=0.5, views=0).
После batch-пересчёта та же запись получит `importance ≈ 0.175 + 0 + 0 + time_decay` — это **резкое падение** без изменения реальной значимости.

---

## Рекомендуемые патчи (на ревью)

### Патч 1 — Унифицировать дефолты consensus/novelty

```sql
-- В тексте fn_recalc_importance заменить:
COALESCE(m.consensus, 0.0) → COALESCE(m.consensus, 0.5)
COALESCE(m.novelty,   0.0) → COALESCE(m.novelty,   0.5)
```

**Обоснование**: При отсутствии кластерных данных честнее использовать неопределённость (0.5), а не нулевой сигнал.

### Патч 2 — Отдельно задокументировать, что `source_trust` не используется в формуле

Добавить комментарий в DDL:
```sql
COMMENT ON TABLE tgscan.source_trust IS
  'Денормализованная копия tgscan.channels.weight для внешних сервисов.
   В формуле importance используется channels.weight напрямую, а не эта таблица.';
```

### Патч 3 — Периодический запуск batch-пересчёта (опционально, не критично сейчас)

Добавить шедулер, вызывающий `/api/scoring/refresh` раз в N часов, чтобы time decay в `bot.messages` актуализировался.

---

## Вывод

**Алгоритм корректен**, но реализован иначе, чем было спроектировано изначально:
- Аддитивная формула с 4 компонентами (35%/25%/20%/15%) вместо мультипликативной
- Нет Wilson Score — вместо него нормированный логарифм с поправкой на penetration rate
- Экспоненциальный time decay вместо Hacker News gravity
- `source_trust` таблица существует, но не используется в самом вычислении

Все edge cases (views=0, NULL subscribers, пустой контент) обработаны корректно.

Основной операционный риск: importance в `bot.messages` не обновляется автоматически по времени — нужен регулярный batch-пересчёт.
