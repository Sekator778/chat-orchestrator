# News System API Reference

> **Version**: 1.0
> **Base URL**: `http://localhost:8080` (configurable via `SERVER_PORT`)
> **Swagger UI**: `http://localhost:8080/swagger-ui.html` (when `HTTP_ENABLED=true`)

---

## Overview

The News System API provides endpoints for:
- Managing bot personas for news publishing
- Generating news digests manually or on schedule
- Managing source trust scores and categories
- Testing and monitoring the news system

All endpoints return JSON responses and use reactive (non-blocking) processing.

---

## Authentication

Currently, the API does not require authentication. For production use, implement Spring Security.

**Future**: Add API key or OAuth2 authentication.

---

## Persona Management

### GET /api/admin/persona

Lists all persona bundles (bot IDs and languages).

**Response**:
```json
[
  {
    "botId": "1234567890:AAH...",
    "languages": ["en", "ru"]
  }
]
```

**Example**:
```bash
curl http://localhost:8080/api/admin/persona
```

---

### GET /api/admin/persona/{botId}

Lists all personas for a specific bot.

**Path Parameters**:
- `botId` (string) - Bot token

**Response**:
```json
[
  {
    "id": 1,
    "botId": "1234567890:AAH...",
    "language": "en",
    "name": "Crypto News Pro",
    "description": "Professional crypto news",
    "behavior": "Factual updates",
    "traits": "professional,concise",
    "limitations": "No advice",
    "metadata": {
      "target_chat": -1001234567890,
      "schedule": "0 0 8,20 * * *",
      "style": "professional",
      "topics": ["EXCHANGE", "NEWS"],
      "lookback_hours": 6,
      "top_clusters": 3
    },
    "createdAt": "2026-01-10T10:00:00Z",
    "updatedAt": "2026-01-10T12:00:00Z"
  }
]
```

**Example**:
```bash
curl http://localhost:8080/api/admin/persona/1234567890:AAH...
```

---

### GET /api/admin/persona/{botId}/{lang}

Gets a specific persona by bot ID and language.

**Path Parameters**:
- `botId` (string) - Bot token
- `lang` (string) - Language code (en, ru, uk, etc.)

**Response**: Same as above (single persona object)

**Status Codes**:
- `200 OK` - Persona found
- `404 Not Found` - Persona doesn't exist

**Example**:
```bash
curl http://localhost:8080/api/admin/persona/1234567890:AAH.../en
```

---

### PUT /api/admin/persona/{botId}/{lang}

Creates or updates a persona.

**Path Parameters**:
- `botId` (string) - Bot token
- `lang` (string) - Language code

**Request Body**:
```json
{
  "name": "Crypto News Pro Test",
  "description": "Professional crypto news aggregator (TEST)",
  "behavior": "Provide factual, timely news",
  "traits": "professional,concise,objective",
  "limitations": "No financial advice",
  "metadata": {
    "target_chat": -1001234567890,
    "schedule": "",
    "style": "professional",
    "topics": ["EXCHANGE", "NEWS"],
    "lookback_hours": 6,
    "top_clusters": 3,
    "test_mode": true
  }
}
```

**Response**: Updated persona object (200 OK)

**Example**:
```bash
curl -X PUT http://localhost:8080/api/admin/persona/BOT_TOKEN/en \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Test Persona",
    "description": "Test",
    "behavior": "Test behavior",
    "traits": "test",
    "limitations": "None",
    "metadata": {
      "target_chat": -1001234567890,
      "style": "professional"
    }
  }'
```

---

## News Digest Operations

### POST /api/news/digest/generate

Manually generates a news digest from recent messages.

**Request Body**:
```json
{
  "lookbackHours": 6,
  "maxMessages": 10,
  "language": "en"
}
```

**Fields**:
- `lookbackHours` (int) - How far back to look for news (hours)
- `maxMessages` (int) - Maximum messages to include in digest
- `language` (string) - Target language for digest (en, ru, uk)

**Response**:
```json
{
  "digest": "## Top Crypto News\n\n- Binance announced new listing...\n- Bitcoin reached $50k...\n- ...",
  "messagesIncluded": 8,
  "language": "en"
}
```

**Status Codes**:
- `200 OK` - Digest generated successfully
- `500 Internal Server Error` - Generation failed

**Example**:
```bash
curl -X POST http://localhost:8080/api/news/digest/generate \
  -H "Content-Type: application/json" \
  -d '{
    "lookbackHours": 6,
    "maxMessages": 10,
    "language": "en"
  }'
```

**Notes**:
- Uses `NewsSynthesisService` to generate digest
- Only includes primary messages from clusters
- Filters by importance score
- May return "No significant news" if no messages found

---

### GET /api/news/cluster/{clusterId}/summary

Generates a summary for a specific message cluster.

**Path Parameters**:
- `clusterId` (string) - Cluster ID (from `bot.messages.cluster_id`)

**Query Parameters**:
- `language` (string, optional) - Target language (default: en)

**Response**: Plain text summary

**Example**:
```bash
curl "http://localhost:8080/api/news/cluster/abc123def456/summary?language=en"
```

**Status Codes**:
- `200 OK` - Summary generated
- `404 Not Found` - Cluster doesn't exist or is empty
- `500 Internal Server Error` - Generation failed

---

## Source Trust Management

### GET /api/sources

Lists all channels with trust scores and categories.

**Query Parameters**: None

**Response**:
```json
[
  {
    "channelId": -1001234567890,
    "title": "Binance Official",
    "username": "binance_official",
    "trustScore": 0.95,
    "isOfficial": true,
    "category": "EXCHANGE",
    "manualOverride": true
  },
  {
    "channelId": -1009876543210,
    "title": "CoinDesk",
    "username": "coindesk",
    "trustScore": 0.75,
    "isOfficial": false,
    "category": "NEWS",
    "manualOverride": true
  }
]
```

**Example**:
```bash
curl http://localhost:8080/api/sources
```

**Notes**:
- Only returns channels with `join_status = 'JOINED'`
- Ordered by trust score (highest first)
- Limited to 100 results

---

### PUT /api/sources/{channelId}/trust

Updates trust score and category for a channel.

**Path Parameters**:
- `channelId` (long) - Channel ID (from `tgscan.channels.id`)

**Request Body**:
```json
{
  "trustScore": 0.85,
  "isOfficial": true,
  "category": "EXCHANGE"
}
```

**Fields**:
- `trustScore` (double) - Trust score (0.0 to 1.0)
- `isOfficial` (boolean, optional) - Is channel officially verified
- `category` (string, optional) - Category name

**Response**: `"Updated"` (200 OK)

**Example**:
```bash
curl -X PUT http://localhost:8080/api/sources/-1001234567890/trust \
  -H "Content-Type: application/json" \
  -d '{
    "trustScore": 0.90,
    "isOfficial": true,
    "category": "EXCHANGE"
  }'
```

**Notes**:
- Sets `manual_override = true` to prevent automatic updates
- Creates new record if channel doesn't have trust entry
- Updates existing record if already exists (UPSERT)

---

### GET /api/sources/categories

Lists all available source categories.

**Response**:
```json
[
  "COMMUNITY",
  "EXCHANGE",
  "INFLUENCER",
  "NEWS",
  "PROJECT"
]
```

**Example**:
```bash
curl http://localhost:8080/api/sources/categories
```

**Notes**:
- Returns distinct categories from `tgscan.source_trust` table
- Alphabetically sorted

---

## Error Responses

All endpoints may return error responses:

**400 Bad Request**:
```json
{
  "error": "Invalid request",
  "message": "lookbackHours must be positive"
}
```

**404 Not Found**:
```json
{
  "error": "Not found",
  "message": "Persona not found for botId=... lang=en"
}
```

**500 Internal Server Error**:
```json
{
  "error": "Internal server error",
  "message": "Failed to generate digest: DeepSeek API timeout"
}
```

---

## Swagger/OpenAPI Documentation

Interactive API documentation is available via Swagger UI:

**URL**: `http://localhost:8080/swagger-ui.html`

**Features**:
- Browse all endpoints
- View request/response schemas
- Try API calls directly from browser
- Download OpenAPI spec

**OpenAPI JSON**: `http://localhost:8080/v3/api-docs`

---

## Rate Limiting

Currently, no rate limiting is implemented.

**Future**: Add rate limiting with:
- Per-IP limits
- Per-bot-token limits for persona operations
- LLM API rate limit tracking

---

## Monitoring Endpoints

### GET /actuator/health

Health check endpoint (if Spring Actuator enabled).

**Response**:
```json
{
  "status": "UP"
}
```

### GET /actuator/metrics

Application metrics (if Spring Actuator enabled).

---

## WebSocket (Future)

**Planned**: WebSocket endpoint for real-time digest updates.

```
ws://localhost:8080/ws/digests
```

---

## Examples

### Complete Workflow: Create and Test Persona

**1. Create persona**:
```bash
curl -X PUT http://localhost:8080/api/admin/persona/TEST_BOT_TOKEN/en \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Test News Bot",
    "description": "Testing news system",
    "behavior": "Provide news",
    "traits": "professional",
    "limitations": "None",
    "metadata": {
      "target_chat": -1001234567890,
      "style": "professional",
      "topics": ["EXCHANGE"],
      "lookback_hours": 6,
      "top_clusters": 3,
      "test_mode": true
    }
  }'
```

**2. Generate test digest**:
```bash
curl -X POST http://localhost:8080/api/news/digest/generate \
  -H "Content-Type: application/json" \
  -d '{
    "lookbackHours": 6,
    "maxMessages": 10,
    "language": "en"
  }'
```

**3. View all personas**:
```bash
curl http://localhost:8080/api/admin/persona
```

**4. Update source trust**:
```bash
curl -X PUT http://localhost:8080/api/sources/-1001234567890/trust \
  -H "Content-Type: application/json" \
  -d '{
    "trustScore": 0.95,
    "isOfficial": true,
    "category": "EXCHANGE"
  }'
```

---

## Client Libraries

Currently, no official client libraries exist. Use standard HTTP clients:

- **JavaScript**: `fetch()`, `axios`
- **Python**: `requests`, `httpx`
- **Java**: `WebClient`, `RestTemplate`
- **cURL**: Command-line testing

---

## Security Considerations

**Current**: No authentication

**Recommended for Production**:
1. Add Spring Security
2. Implement API keys or OAuth2
3. Rate limiting per user/IP
4. CORS configuration for frontend
5. HTTPS only (no HTTP)
6. Input validation and sanitization
7. SQL injection prevention (already uses parameterized queries)

---

## Support

For issues or questions:
- Check Swagger UI for interactive docs
- Review logs: `logs/application.log`
- Test with cURL examples above
- Verify configuration in `application.yml`

---

**END OF API REFERENCE**
