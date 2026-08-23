package com.example.telegramuserbot.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.r2dbc.repository.Query;

import java.lang.reflect.Method;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the news-recall contract that a June-2026 defect audit was written about.
 * <p>
 * The recall used to be a source-blind {@code ORDER BY value_score DESC LIMIT 150}.
 * Web-outlet rows carry a flat importance floor while Telegram rows earn theirs
 * from engagement, so the whole window filled with web items and Telegram posting
 * stopped dead for nine days before anyone noticed. The fix normalizes each row
 * against its own source class with {@code PERCENT_RANK()} before the global cut.
 * <p>
 * Two more findings from the same audit are pinned here: a post is excluded from
 * recall only for the chat it was actually sent to, and only when it was really
 * sent — a transient failure used to bury a good item forever, cross-chat.
 * <p>
 * These assertions read the SQL text rather than execute it. That is deliberate:
 * the regressions this guards against are edits to the query itself, and the test
 * has to run in {@code mvn test}, where there is no database. Behavioural coverage
 * belongs in an integration test once the Testcontainers base exists.
 */
class NewsRecallQueryContractTest {

    private static String queryOf(Class<?> repository, String method, Class<?>... parameterTypes) {
        try {
            Method target = repository.getMethod(method, parameterTypes);
            Query query = target.getAnnotation(Query.class);
            assertThat(query)
                    .as("%s.%s must carry an @Query", repository.getSimpleName(), method)
                    .isNotNull();
            // Collapse formatting so the assertions survive re-indentation of the SQL.
            return query.value().replaceAll("\\s+", " ").trim();
        } catch (NoSuchMethodException e) {
            throw new AssertionError(
                    "%s.%s is gone — if it was renamed, move this contract test with it"
                            .formatted(repository.getSimpleName(), method), e);
        }
    }

    private static String recallQuery() {
        return queryOf(MessageRepository.class, "findUnpostedNewsCandidatesForPersona",
                Instant.class, int.class, double.class, long.class, int.class,
                String.class, boolean.class, String[].class);
    }

    @Test
    @DisplayName("recall ranks within source class before the global cut")
    void recallIsSourceFair() {
        assertThat(recallQuery())
                .as("a source-blind ORDER BY lets flat-floored web rows take the whole window")
                .contains("ORDER BY PERCENT_RANK() OVER ( PARTITION BY (tc.outlet_trust IS NOT NULL)");
    }

    @Test
    @DisplayName("raw value stays the tiebreaker, not the primary ordering")
    void rawValueIsOnlyTheTiebreaker() {
        assertThat(recallQuery())
                .contains(") DESC, (m.importance * ln(greatest(tc.subscribers, 2))) DESC NULLS LAST LIMIT :scanLimit");
    }

    @Test
    @DisplayName("a post is excluded only from the chat it was sent to")
    void dedupIsChatScoped() {
        assertThat(recallQuery())
                .as("persona-scoped dedup let two personas post the same item into one chat seconds apart")
                .contains("SELECT message_id FROM bot.news_posts WHERE target_chat_id = :targetChatId");
    }

    @Test
    @DisplayName("only a sent post excludes an item from recall")
    void dedupCountsOnlySentPosts() {
        assertThat(recallQuery())
                .as("without the status predicate one transient send failure buries the item forever")
                .contains("AND status = 'SENT' )");
    }

    @Test
    @DisplayName("the daily cap counts sent posts only")
    void dailyCapCountsOnlySentPosts() {
        String capQuery = queryOf(NewsPostRepository.class,
                "countByPersonaBotIdAndTargetChatIdAndPostedAtAfter",
                String.class, Long.class, Instant.class);
        assertThat(capQuery)
                .as("failed sends must not burn cap slots")
                .contains("AND status = 'SENT'");
    }
}
