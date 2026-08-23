package com.example.telegramuserbot.service.safety;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link OutboundReplyGuard} is the last thing standing between an LLM draft that
 * says "I am a bot" and a real chat. It was documented as a single choke point in
 * {@code TelegramMessageSenderImpl} — and the two paths that carry almost all the
 * traffic, the reactive reply and the pending queue, never went through it: both
 * build their own {@code TdApi.SendMessage} and hand it to the client facade
 * directly, with a comment in the reply path stating the opposite.
 * <p>
 * A unit test of one send path would not have caught that, because the defect was
 * a path nobody thought to test. So this test asks the question structurally: any
 * class that assembles a SendMessage is a send path, and a send path must consult
 * the guard. A new one that forgets fails here.
 */
class OutboundGuardCoverageTest {

    private static final Path SOURCE_ROOT = Path.of("src/main/java");

    /**
     * Command replies are our own fixed strings, not model output, and are answered
     * to the person who typed the command. The guard's denylist is aimed at
     * generated text; a command like /status has nothing to moderate.
     */
    private static final Set<String> EXEMPT = Set.of("TelegramListenerService.java");

    @Test
    @DisplayName("every class that builds a SendMessage consults the outbound guard")
    void everySendPathIsGuarded() throws IOException {
        List<String> unguarded = new ArrayList<>();

        try (Stream<Path> sources = Files.walk(SOURCE_ROOT)) {
            for (Path source : sources.filter(p -> p.toString().endsWith(".java")).toList()) {
                String body = Files.readString(source, StandardCharsets.UTF_8);
                if (!body.contains("new TdApi.SendMessage(")) {
                    continue;
                }
                String fileName = source.getFileName().toString();
                if (EXEMPT.contains(fileName)) {
                    continue;
                }
                if (!body.contains("OutboundReplyGuard") && !body.contains("outboundReplyGuard")) {
                    unguarded.add(SOURCE_ROOT.relativize(source).toString());
                }
            }
        }

        assertThat(unguarded)
                .as("these build an outbound message without passing it through OutboundReplyGuard — "
                        + "either apply the guard, or add the file to EXEMPT with a reason")
                .isEmpty();
    }

    @Test
    @DisplayName("the exemption list still points at files that exist")
    void exemptionsAreNotStale() {
        for (String exempt : EXEMPT) {
            assertThat(findByName(exempt))
                    .as("%s is exempt from the guard check but no longer exists — drop the entry", exempt)
                    .isNotNull();
        }
    }

    private static Path findByName(String fileName) {
        try (Stream<Path> sources = Files.walk(SOURCE_ROOT)) {
            return sources.filter(p -> p.getFileName().toString().equals(fileName)).findFirst().orElse(null);
        } catch (IOException e) {
            throw new AssertionError("could not scan " + SOURCE_ROOT, e);
        }
    }
}
