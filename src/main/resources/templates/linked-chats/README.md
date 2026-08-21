# Linked Chats Configuration Templates

This directory contains YAML configuration templates for linked channel-discussion pairs.

## Available Templates

### minimal-reaction.yaml
**Based on:** `config_Example.sql`
- Simple short reactions with light emotion
- 8 messages per day maximum
- Temperature: 0.35 (moderate creativity)
- Tone: CASUAL
- Use for: General discussion groups with Russian language

### low-engagement-followup.yaml
**Based on:** `config_low_engagement_followup.sql`
- Neutral follow-up reactions after human engagement
- 5 messages per day maximum
- Temperature: 0.18 (very conservative)
- Tone: NEUTRAL
- Use for: Low-activity groups, wait for humans to reply first

## Quick Start

### 1. Create New Template

Copy existing template:
```bash
cp minimal-reaction.yaml my-template.yaml
```

### 2. Edit Configuration

```yaml
templateName: my-template
description: Your description here

channel:
  enabled: false
  maxDailyMessages: 10
  # ...

discussion:
  enabled: true
  maxDailyMessages: 5
  # ...
```

### 3. Use in Code

```java
@Autowired
private LinkedChatsTemplateFactory factory;

LinkedChatsTemplate template = factory.loadTemplate("my-template");
```

### 4. Test

```bash
mvn test -Dtest=LinkedChatsTemplateLoaderTest
```

## Important Fields

### Rate Limits
- `maxMessagesPerDay` - Total daily message limit
- `maxMessagesPerHour` - Hourly message limit
- `cooldownAfterLimitMinutes` - Cooldown after hitting limit

### LLM Parameters
- `temperature` - Creativity (0.0-1.0, lower = more conservative)
- `maxTokens` - Maximum response length
- `topP` - Nucleus sampling parameter

### Trigger Conditions
- `timeDelaySeconds` - Delay before responding
- `probabilityPercent` - Chance of responding (0-100)
- `minimumGapMinutes` - Minimum time between responses

## Response Styles

- `CONCISE` - Short, to the point
- `DETAILED` - Longer, more informative
- `CONVERSATIONAL` - Natural dialogue

## Response Tones

- `CASUAL` - Friendly, light emotion
- `NEUTRAL` - Factual, no emotion
- `FORMAL` - Professional, structured
- `HUMOROUS` - Playful, jokes

## Response Lengths

- `SHORT` - 1-2 sentences
- `MEDIUM` - 2-4 sentences
- `LONG` - 4+ sentences

## Documentation

Full documentation: `/tasks_and_manuals/chat_configuration_and_linked_chats.md`
