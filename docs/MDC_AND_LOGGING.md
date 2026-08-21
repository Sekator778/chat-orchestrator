# MDC и раздельные логи

## TraceId и MDC
- В `ResponseOrchestrator` вся цепочка оборачивается в `MdcContext.withTrace(chatId, messageId, pipeline)` с полями:
  - `traceId` — UUID (один на цепочку обработки)
  - `chatId` / `messageId` — идентификаторы TDLib
  - `pipeline` — `reply` (ветки CONCISE/ENHANCED внутри)
- Эти поля пишутся в логи через `%X{traceId} %X{chatId} %X{messageId} %X{pipeline}`.
- При поиске по логу используйте `traceId` для сквозного просмотра всей цепочки обработки сообщения.

## Раздельные логи
- Основной: `logs/app.log`
- LLM: `logs/llm-interactions.log`
- Search: `logs/search-operations.log`
- Остальные (telegram, sync и т.д.) сохраняются из `logback-spring.xml`.
- Формат строк: `timestamp level logger [traceId chatId messageId pipeline] message`

## Где настраивать
- Файл: `src/main/resources/logback-spring.xml` — шаблон уже включает MDC-поля для app/llm/search.
- Utility: `MdcContext` (реактивный) — ставит/чистит MDC в цепочке.
