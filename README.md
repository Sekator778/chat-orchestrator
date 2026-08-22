# Chat Orchestrator — AI-driven Telegram automation

> 🤖 **Developed and continuously improved by [ai-delivery](https://github.com/Sekator778/ai-delivery)** — an autonomous AI delivery pipeline that turns a single task request into a reviewed, mergeable pull request. Features here were specified, implemented, tested, and reviewed end-to-end by that pipeline; this repository is the living showcase of its output.

A reactive **Spring Boot** platform that automates LLM-driven conversations and message synchronization across Telegram chats. It integrates with the Telegram API via TDLight, uses DeepSeek (or any OpenAI-compatible LLM) for response generation, Apache Kafka for asynchronous message processing, and an R2DBC + PostgreSQL + Liquibase persistence layer — built end-to-end on a non-blocking, reactive stack.

## Key Features

*   **Telegram Integration (TDLight)**: Connects directly to the Telegram API as a user, enabling comprehensive chat management and message processing.
*   **Intelligent Responses (DeepSeek/LLMs)**:
    *   **Natural-Language Quality**: Persona management and response variation produce natural, context-appropriate replies tuned per channel.
    *   **Personalization**: Tailors AI responses based on individual user preferences (communication style, response length, language, personality traits, relationship context).
    *   **Contextual Understanding**: Utilizes chat history and configurable context windows to generate relevant and coherent replies.
*   **Chat Configuration Management**:
    *   **Granular Control**: Allows administrators to configure AI behavior, response templates, trigger conditions, LLM parameters, rate limits, and topic restrictions on a per-channel basis.
    *   **Bulk Operations**: Supports copying, resetting, importing, and exporting configurations between channels.
*   **Message Synchronization**:
    *   **History Sync**: Initiates and manages synchronization jobs to fetch historical messages from Telegram channels, with progress tracking and retry mechanisms.
    *   **Auto-Sync**: Configurable automatic synchronization for channels to keep local message history up-to-date.
*   **Web Search Integration**:
    *   **Intelligent Search**: Analyzes messages to determine if a web search is beneficial and extracts relevant queries.
    *   **Search Configuration**: Per-chat settings for enabling/disabling search, auto-search, and custom trigger patterns.
    *   **Quota Management**: Tracks and enforces search quotas.
*   **Asynchronous Processing (Kafka)**: Utilizes Kafka for reliable and scalable processing of Telegram messages and other events, ensuring responsiveness and decoupling of components.
*   **Reactive Programming (Project Reactor)**: Built with a reactive stack for efficient handling of concurrent operations and I/O-bound tasks.
*   **Persistence (R2DBC, PostgreSQL, Liquibase)**: Stores application data, configurations, and message history in a PostgreSQL database, with schema management handled by Liquibase.
*   **Admin & Debug APIs (SpringDoc OpenAPI)**: Provides a comprehensive set of RESTful APIs for managing the bot, monitoring its status, and debugging Telegram interactions, documented with Swagger UI.

## Architecture Overview

The application is a Spring Boot microservice leveraging a reactive programming model.

*   **`config`**: Contains configuration classes for the Telegram client (TDLight), OpenAPI documentation, WebClient for external API calls (e.g., DeepSeek), and Kafka consumers.
*   **`controller`**: Exposes RESTful APIs for administrative tasks, chat configuration, search functionality, synchronization management, and debugging Telegram interactions.
*   **`service`**: The core business logic layer, divided into sub-packages for:
    *   `admin`: High-level administrative operations.
    *   `command`: Handling specific Telegram commands.
    *   `config`: Detailed management of chat configurations, templates, and rules.
    *   `decision`: Logic for deciding bot actions.
    *   `generation`: AI response generation.
    *   `humanization`: Logic for making AI responses more human-like.
    *   `llm`: Integration with Large Language Models (DeepSeek, Enhanced LLM, Humanized LLM).
    *   `orchestration`: Coordinating complex workflows.
    *   `persistence`: Managing data storage.
    *   `personalization`: User-specific AI adjustments.
    *   `processing`: Message processing pipeline.
    *   `search`: Web search integration.
    *   `startup`: Initial synchronization and chat discovery.
    *   `sync`: Management of message history synchronization jobs.
    *   `tracking`: Monitoring and metrics.
    *   `util`: General utility classes.
*   **`repository`**: Defines R2DBC repositories for interacting with the PostgreSQL database, managing entities like `User`, `Channel`, `ChatConfig`, `MessageEntity`, `SyncJob`, `ResponseTemplate`, `TriggerCondition`, `LlmParameters`, `RateLimits`, `TopicRestriction`, and `SearchConfig`.
*   **`domain`**: Contains the JPA/R2DBC entities and enums representing the application's data model.
*   **`dto`**: Data Transfer Objects used for API requests/responses and internal communication.

## Technologies Used

*   **Java 21**
*   **Spring Boot 3.x** (WebFlux, Data R2DBC, Kafka, Cache)
*   **Project Reactor**
*   **TDLight Java Client**
*   **DeepSeek API** (via `openai-java` client)
*   **Apache Kafka**
*   **PostgreSQL**
*   **Liquibase**
*   **Maven**
*   **Swagger UI / SpringDoc OpenAPI**
*   **Testcontainers** (for integration testing)
*   **Caffeine & Guava** (for caching)

## Setup and Running

### Prerequisites

*   Java 21 SDK
*   Maven
*   Docker (for Testcontainers, PostgreSQL, Kafka)
*   Telegram API ID and API Hash (obtain from [my.telegram.org](https://my.telegram.org/apps))
*   DeepSeek API Key

### Build

To compile the project and run tests:

```bash
mvn clean package
```

To skip integration tests (faster build):

```bash
mvn clean package -DskipITs
```

### Configuration

The application uses `application.yml` for configuration, with profile-specific overrides (e.g., `application-dev.yml`, `application-test.yml`).

**Key configuration properties (usually set via environment variables or `application-dev.yml`):**

*   `telegram.api.id`: Your Telegram API ID.
*   `telegram.api.hash`: Your Telegram API Hash.
*   `tdlib.databaseDirectory`: Path to store TDLib database files (e.g., `tdlib_db`).
*   `tdlib.filesDirectory`: Path to store TDLib downloaded files (e.g., `tdlib_files`).
*   `deepseek.apiUrl`: URL for the DeepSeek API (e.g., `https://api.deepseek.com/chat/completions`).
*   `deepseek.apiKey`: Your DeepSeek API Key.
*   `bot.instance-id`: Logical identifier for the running bot account. Every channel/config/sync row is tagged with this value so multiple bot instances can share one database without stepping on each other. Set a unique value (e.g., `bot-a`, `bot-b`) per TDLib session.
*   `sync.channels.scheduler.spread-minutes`: Optional staggering window for the daily channel pipeline. When > 0, each instance waits up to this many minutes (based on its `bot.instance-id` hash) before running the pipeline, preventing all bots from hammering TDLib simultaneously.
*   `sync.messages.scheduler.spread-minutes`: Optional staggering window (minutes) for the 5-hour message-sync scheduler; works the same way as the channel spread property.
*   `spring.r2dbc.url`, `spring.r2dbc.username`, `spring.r2dbc.password`: PostgreSQL connection details.
*   `spring.kafka.bootstrap-servers`: Kafka broker addresses.

### Run

1.  **Start Dependencies (PostgreSQL, Kafka, TDLib)**:
    Ensure your PostgreSQL database, Kafka brokers, and TDLib native libraries are accessible. For development, you might use Docker Compose or local installations.

2.  **Run the Application**:

    ```bash
    mvn spring-boot:run -Dspring-boot.run.profiles=dev
    ```
    This will start the application with the `dev` profile, which typically includes configurations for local development.

    Alternatively, you can build the JAR and run it:

    ```bash
    java -jar target/chat-orchestrator-1.0.0.jar --spring.profiles.active=dev
    ```

3.  **Initial Telegram Login**:
    Upon first run, the bot will prompt for a phone number and verification code in the console to authorize with Telegram. Follow the instructions in the console.

### Accessing the API Documentation

Once the application is running, you can access the Swagger UI at:
`http://localhost:8080/swagger-ui.html` (assuming default port 8080).

## Frontend

The frontend in `frontend/` requires **Node.js 24.x** (pinned in `frontend/.nvmrc`).

## Development Guidelines

Follow conventional-commit style and the existing package layout (see **Architecture Overview** above). Run `mvn clean package` before opening a pull request; integration tests use Testcontainers. Secret scanning runs on every push via `.github/workflows/gitleaks.yml` and locally via the `.pre-commit-config.yaml` gitleaks hook. All pull requests must pass the CI build (`.github/workflows/ci.yml`, `mvn -B -ntp verify`) before they can be merged. Open them against `dev`, the integration branch (see [CONTRIBUTING.md](CONTRIBUTING.md)); promoting `dev` into `main` publishes a macOS deploy jar that the local stand picks up the next time it is started (see [docker/atlas/README.md](docker/atlas/README.md)).

## Data Model Highlights

*   **`User`**: Stores Telegram user information and personalization settings for AI interactions.
*   **`Channel`**: Represents a Telegram chat (channel, group, private chat) managed by the bot.
*   **`ChatConfig`**: Core configuration for each channel, including AI enablement, prompt templates, and general settings.
*   **`MessageEntity`**: Stores synchronized Telegram messages, forming the basis for conversation context.
*   **`SyncJob`**: Tracks the status and progress of message synchronization operations.
*   **`ResponseTemplate`**: Defines predefined response structures and styles for AI.
*   **`TriggerCondition`**: Rules that determine when the bot should respond to a message.
*   **`LlmParameters`**: Specific parameters for the Large Language Model (e.g., model name, temperature).
*   **`RateLimits`**: Configurable limits on message and token generation to prevent abuse.
*   **`TopicRestriction`**: Rules to restrict bot responses based on message content or categories.
*   **`SearchConfig`**: Configuration for web search functionality within a chat.

## License

Released under the [MIT License](LICENSE).
