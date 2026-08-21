# Гайд: Автоматические реакции персон

> Как настроить персону так, чтобы она реагировала 👍🔥💯 на посты в нужных каналах.

---

## Как это работает

```
Новый пост в канале
      ↓
TelegramListenerService получает UpdateNewMessage
      ↓
ReactionDetectionService проверяет:
  - есть ли конфиг для этого канала и персоны?
  - не превышен ли дневной лимит?
  - прошло ли достаточно времени с последней реакции в этом канале?
      ↓
Создаёт запись PENDING в bot.persona_reaction_log
с рандомной задержкой (по умолчанию 1–3 мин в dev, 5–40 мин в prod)
      ↓
ReactionExecutorScheduler (каждые 60 сек) проверяет PENDING записи
      ↓
ReactionExecutionServiceImpl отправляет реакцию через TDLib
      ↓
Статус → DONE / FAILED / FLOOD_WAIT
```

---

## Шаг 1 — Открыть раздел реакций

Перейти в раздел **Persona Reactions** в боковом меню фронтенда.

Вкладки:
- **Обзор** — статистика за день, пул эмодзи
- **Конфигурации** — список всех правил (кто + в каком канале)
- **История** — лог реакций с фильтрами

---

## Шаг 2 — Создать конфигурацию

Таб **Конфигурации** → кнопка **+ Добавить конфигурацию**.

### Поля формы

| Поле | Что заполнить | Пример |
|------|---------------|--------|
| **Персона** | Выбрать из списка | Persona One / Persona Two |
| **ID канала** | Telegram-id чата (всегда отрицательный для групп/каналов) | `-1001234567890` |
| **Максимум реакций в день** | Сколько реакций ставить в этот канал за день (1–20) | `5` |
| **Конфигурация активна** | Чекбокс — включить сразу или позже | ✅ |

> **Важно:** персона и канал задаются один раз при создании. После сохранения эти поля менять нельзя — нужно удалить и создать новую конфигурацию.

### Как узнать ID канала

1. В Telegram Web: открыть канал, в URL будет `https://web.telegram.org/k/#-1001234567890` → ID = `-1001234567890`
2. Через бота: переслать любое сообщение из канала в @userinfobot
3. Из логов приложения: `grep "MESSAGE RECEIVED" logs/telegram-interactions.log | grep "Chat:"`

---

## Шаг 3 — Убедиться что для КАЖДОЙ персоны нужен отдельный конфиг

Одна конфигурация = одна персона + один канал.

Если нужно чтобы **оба** бота реагировали на посты в одном канале — создать **два** конфига:

```
Конфиг 1: Persona One (2000000001) → канал -1001234567890, max 5/день
Конфиг 2: Persona Two (2000000002) → канал -1001234567890, max 3/день
```

---

## Шаг 4 — Управление конфигурацией

В списке конфигураций (таб **Конфигурации**) на каждой карточке:

- **Вкл/Выкл** — быстро приостановить реакции без удаления конфига
- **Редактировать** — изменить `max_per_day` или статус enabled
- **Удалить** — убрать конфиг полностью

Фильтры: по персоне, по статусу (активные/неактивные).

---

## Шаг 5 — Проверить работу

### На фронте (таб Обзор)

| Метрика | Значение |
|---------|----------|
| Всего конфигов | кол-во созданных правил |
| Активных | enabled = true |
| Ожидают | PENDING — ждут своего времени |
| Сегодня выполнено | DONE за текущий день |
| Ошибок сегодня | FAILED — реакция не прошла |
| Flood Wait | Telegram попросил подождать |

### В базе данных

```sql
-- Статус реакций по персоне
SELECT persona_id, status, COUNT(*)
FROM bot.persona_reaction_log
WHERE created_at > now() - interval '24 hours'
GROUP BY persona_id, status;

-- Последние выполненные реакции
SELECT persona_id, channel_id, message_id, reaction_emoji, executed_at
FROM bot.persona_reaction_log
WHERE status = 'DONE'
ORDER BY executed_at DESC
LIMIT 20;

-- PENDING реакции — что ждёт выполнения
SELECT id, persona_id, channel_id, scheduled_at, attempt_count
FROM bot.persona_reaction_log
WHERE status = 'PENDING'
ORDER BY scheduled_at;
```

### В логах

```bash
# Только реакции — отдельный файл
tail -f logs/persona-reactions.log

# Пример успешного цикла:
# 13:58:39 INFO ReactionExecutionServiceImpl - Sending reaction persona=2000000001 channel=-1001234567890 message=11986272256 emoji=👍
# 13:59:09 INFO ReactionExecutionServiceImpl - Reaction DONE id=1 persona=2000000001 channel=-1001234567890 message=11986272256 emoji=👍
# 13:59:09 INFO ReactionExecutorScheduler - Reaction executor cycle completed: 1 reactions executed
```

---

## Параметры системы (application.yml)

```yaml
persona:
  reaction:
    enabled: true                        # включить систему
    executor-interval-ms: 60000          # как часто планировщик проверяет PENDING (60 сек)
    daily-limit-per-persona: 15          # глобальный дневной лимит на персону (все каналы)
    delay-min-minutes: 5                 # минимальная задержка перед реакцией
    delay-max-minutes: 40                # максимальная задержка (рандом в этом диапазоне)
    min-gap-between-reactions-seconds: 30  # пауза между отправками в одном цикле
    min-gap-same-channel-minutes: 30     # минимум между двумя реакциями в одном канале
    max-concurrent-executions: 5         # сколько реакций брать за один цикл
    flood-wait-backoff-minutes: 60       # откат при FLOOD_WAIT от Telegram
    emoji-pool:
      - emoji: "👍"
        weight: 60    # 60% вероятность
      - emoji: "🔥"
        weight: 30    # 30%
      - emoji: "💯"
        weight: 10    # 10%
```

**Для dev-тестирования** (application-dev.yml):
```yaml
persona:
  reaction:
    enabled: true
    delay-min-minutes: 1   # реакции ставятся через 1–3 минуты
    delay-max-minutes: 3
```

---

## Антидетекшн

Система автоматически:
- Рандомизирует задержку между реакцией и постом (никогда не реагирует мгновенно)
- Контролирует частоту по каналу (`min-gap-same-channel-minutes`)
- Контролирует дневной лимит по персоне (`daily-limit-per-persona`)
- При FLOOD_WAIT от Telegram откладывает реакцию на `flood-wait-backoff-minutes`
- При ошибке делает до 3 попыток с интервалом 5 минут, затем ставит FAILED

---

## Типичные проблемы

| Симптом | Причина | Решение |
|---------|---------|---------|
| Реакции не ставятся | `persona.reaction.enabled: false` | Включить в application-dev.yml |
| "Нет конфигураций" на дашборде | Старая версия без Jackson-фикса | Перезапустить приложение после обновления |
| Реакция зависла в PENDING | `scheduled_at` в будущем | Подождать — задержка 1–40 мин |
| FLOOD_WAIT | Telegram ограничил бота | Реакция перенесётся автоматически на +60 мин |
| FAILED | 3 неудачные попытки | Проверить логи + состояние TDLib клиента персоны |
| Конфиг есть, реакции не создаются | Новые сообщения приходят с `outgoing=true` | Реакции ставятся только на входящие сообщения |
