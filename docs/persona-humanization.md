# Живые персоны — как персона решает, отвечать ли, что и когда

Документ для тестирования ветки `dev` после работ 2026-09-16. Описывает, из чего складывается
«человеческий» ответ персоны, где лежат настройки и как проверить поведение на стенде.

## Путь одного ответа

1. **Кто отвечает** — `ChatPersonaDispatchPlanner`. Кандидаты берутся из `bot.persona_chat_bindings`
   (`reply_enabled`), затем отсеиваются коллектор (`telegram_accounts.is_collector`) и персоны вне
   своего окна активности (`telegram_accounts.active_from/active_until/timezone`).
   *Новое:* `PersonaAddressResolver` определяет, к кому обращено сообщение — ответ на сообщение
   персоны (`reply_to` → `bot.messages.received_by_bot_id`), `@username` или имя персоны в тексте.
   Адресат отвечает всегда (без броска `reply_probability` и без дневной квоты чата — молчать,
   когда к тебе обратились, самый громкий признак бота), остальные молчат. Если адресат спит —
   не отвечает никто. `@username` совпадает только целиком (`@alex` не ловит `@alex_dev`). Без адресата — каждый кандидат бросает свой `reply_probability`
   (`responders.per_persona_decision_enabled=true`, дефолт `responders.default_reply_probability`).
2. **Стоит ли отвечать вообще** — `ResponseDecisionEngine` (`decision_gate.enabled`). Он chat-scoped:
   решает «реагировать на это сообщение или нет», а не «кто». `decision_gate.fail_open` теперь `false`:
   ошибка гейта означает тишину, а не ответ.
3. **Что сказать** — `PromptBuilder` → `PersonaPromptComposer`. Системный промпт — обычный текст на языке
   инструкции (ru / uk / en; `auto` → английский каркас + правило «отвечай на языке собеседника»):
   личность персоны, её привычки письма (`PersonaStyle`), правила чата (`chat_configs.prompt_template`,
   стиль/тон/длина шаблона), правила «как пишут люди в Telegram», легенда участников (`ME:`/`P1:`),
   фоновые знания, текущие дата и время. JSON-дампа конфигурации в промпте больше нет.
   Если персоне нечего добавить, сообщение не к ней и не по её темам, либо это спам — модель
   отвечает ровно `[SKIP]`, и персона молчит.
4. **Как это звучит** — `ReplyHumanizer` (через `ResponsePostProcessor`) на всех трёх хендлерах
   (CONCISE / ENHANCED / MULTI_STAGE) и на генерации дайджестов, ровно один проход: снимает markdown
   (ссылки сохраняются как «текст (url)») и списки из двух и более пунктов, убирает «Отличный вопрос!»,
   «Надеюсь, это поможет», «Great question» и т.п. на языке ответа (короткие шаблонные концовки, никогда
   не единственное предложение), модерирует цепочки тире (диапазоны «10 — 15%» и прямая речь не
   трогаются), применяет привычки персоны (эмодзи целыми юнитами — флаги, тона кожи, ZWJ-семьи;
   строчная буква в начале; без точки в конце). Если в тексте есть признание от первого лица, что это
   ИИ («я ИИ-ассистент», «I'm a language model»), ответ не отправляется (MULTI_STAGE делает одну
   попытку переписать). «Я ассистент режиссёра» или «GPT is a language model» признанием не считаются.
5. **Когда отправить** — `HumanSendPacer` на всех путях отправки (прямой ответ, отложенная очередь,
   sibling-ответы, проактивные сообщения): пауза «прочитать» пропорционально длине входящего, случайная
   пауза «подумать», сдвиг между персонами (всегда), минимальный интервал между сообщениями одной
   персоны в одном чате (слот резервируется в момент планирования, поэтому два параллельных ответа
   одной персоны видят друг друга), индикатор «печатает…» каждые 4 с на время, пропорциональное длине
   ответа (гаснет вместе с отменой отправки). Отложенная очередь отправляет ответы одной персоны в
   одном чате строго последовательно. Проактивные посты: окно активности проверяется и после
   случайного сдвига, таймаут на отправку не режет паузу.

## Настройки персоны

| Где | Что | Эффект |
|---|---|---|
| `bot.bot_personas` (`name`, `description`, `behavior`, `traits`, `limitations`, `language`) | легенда персоны по языкам (`base`/`ru`/`uk`/`en`) | блок «кто ты» в промпте; язык каркаса выбирается по `chat_configs.language` чата |
| `bot.bot_personas.metadata` → `style` (JSONB) | `max_sentences` 1–4, `emoji` none/rare/often, `lowercase_start`, `drop_final_period`, `skip_probability`, `catchphrases[]`, `interests[]`, `timezone` | привычки письма в промпте и в `ReplyHumanizer`; часовой пояс для строки «сейчас …» |
| `bot.bot_personas.reply_to_direct` | отвечать ли в личке | DM-путь |
| `bot.persona_chat_bindings` | `reply_enabled`, `reply_probability` | участие персоны в чате и её склонность отвечать без обращения |
| `bot.telegram_accounts` | `active_from`, `active_until`, `timezone`, `sibling_reply_*` | окно активности; ответы на посты соседних персон |
| `bot.chat_configs` / `response_templates` | `language`, `prompt_template`, `response_style`, `response_tone`, `max_response_length` | правила и длина для конкретного чата |

Пример `metadata` для персоны:

```json
{"style": {"max_sentences": 2, "emoji": "rare", "lowercase_start": true, "drop_final_period": true,
           "skip_probability": 0.15, "catchphrases": ["ну такое", "по факту"],
           "interests": ["крипта", "макро", "ETF"], "timezone": "Europe/Berlin"}}
```

## Глобальные ручки (`bot.app_settings`, перечитываются без рестарта)

| Ключ | Значение по умолчанию (cs084) | Смысл |
|---|---|---|
| `reply_timing.random_delay.min_ms` / `max_ms` | 8000 / 45000 (если было 0) | окно «подумать» перед ответом |
| `reply_timing.read_ms_per_char` / `read_cap_ms` | 35 / 12000 | «прочитать» входящее сообщение |
| `reply_timing.stagger.step_ms` | 3000 | сдвиг между персонами при одновременном ответе |
| `reply_timing.min_gap_same_chat_ms` | 25000 | минимум между сообщениями одной персоны в одном чате |
| `reply_timing.max_pre_send_ms` | 180000 | потолок суммарной паузы |
| `reply_timing.typing.ms_per_char` / `cap_ms` | 60 / 20000 (если было 8000) | длительность «печатает…» |
| `proactive.engagement.jitter_max_minutes` | 20 | случайный сдвиг ежедневного проактивного сообщения |
| `decision_gate.fail_open` | false | ошибка гейта → тишина |
| `responders.default_reply_probability` | не менялось | склонность отвечать без обращения |
| `chain_limit.max_bot_messages_per_post` | 4 | предел подряд идущих сообщений ботов |

## Что удалено как мёртвый или сломанный код

`AntiDetectionService(Impl)`, `AntiDetectionMonitoringService`, `ResponseRefinerService(Impl)`,
`HumanizationDataInitializer`, `ResponseVariation` + репозиторий, `humanization/helper/*`,
`PromptJsonSerializer`, канонные ответы `PersonaService` («Да я не особо люблю селфи…»),
мёртвые блоки `application.yml` (`bot.reply-timing.*`, `bot.decision-gate.*` кроме
`cooldown-enabled`, `humanization.*`), заглушки медиа в `LlmConversationFormatter`, неиспользуемый
`ResponseOrchestrator.maybeQueuePending`. Таблица `bot.response_variations` в БД остаётся, код её
не читает.

## Как проверить на стенде

```sql
-- кто, когда и что отвечал
SELECT chat_id, received_by_bot_id, date, left(content, 120)
FROM bot.messages WHERE is_outgoing ORDER BY date DESC LIMIT 30;
-- решения и пропуски
SELECT triggered_at, status, skip_reason, left(final_response, 100)
FROM bot.llm_queries ORDER BY triggered_at DESC LIMIT 30;
-- текущие ручки
SELECT name, value FROM bot.app_settings WHERE name LIKE 'reply_timing.%' OR name LIKE 'decision_gate.%';
```

В `logs/app.log` смотреть строки `RESPONDERS` (кто выбран), `addressed persona` (адресность),
`TG TIMING` (паузы и «печатает»), `[SKIP]`/`молчим` (осознанная тишина).

Ожидаемое поведение: ответ на сообщение персоны получает именно она; две персоны не пишут в одну
секунду; ответы короткие, без списков и markdown, на языке чата; на спам и сообщения «не к ней»
персона молчит; ночью (вне `active_from/active_until`) — тишина.
