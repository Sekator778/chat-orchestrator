package com.example.telegramuserbot.service.command;

import com.example.telegramuserbot.domain.Channel;
import com.example.telegramuserbot.repository.ChannelRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CommandServiceImplTest {

    private static final long ADMIN_USER_ID = 1000000001L;

    private CommandServiceImpl commandService;
    private ChannelRepository channelRepository;
    private List<Channel> channels;

    @BeforeEach
    void setUp() {
        channels = new ArrayList<>();
        channelRepository = (ChannelRepository) Proxy.newProxyInstance(
                ChannelRepository.class.getClassLoader(),
                new Class[]{ChannelRepository.class},
                new ChannelRepositoryInvocationHandler()
        );

        commandService = new CommandServiceImpl(
                ADMIN_USER_ID, // allowedCommandChatId (admin's private chat ID)
                null, // ConfigurationService
                null, // UserService
                null, // SyncOrchestrationService
                null, // MessageRepository
                null, // SearchConfigurationService
                null, // ChannelMessageSynchronizationService
                null, // MessageDeletionService
                null, // BotInfoService
                channelRepository,
                null, // ChannelService
                null  // ShutdownService
        );
    }

    @Test
    void listChannelsReturnsEmptyMessageWhenNoChannels() {
        channels.clear();

        // chatId must equal senderId (ADMIN_USER_ID) for private chat validation
        Optional<String> response = commandService
                .processCommand(ADMIN_USER_ID, 1L, ADMIN_USER_ID, "/list_channel")
                .block();

        assertThat(response).isPresent();
        assertThat(response.get()).isEqualTo("Список відомих каналів порожній.");
    }

    @Test
    void listChannelsReturnsSortedChannelList() {
        channels.clear();
        channels.add(channel(-1002L, "Zeta Channel"));
        channels.add(channel(-1001L, "alpha reports"));
        channels.add(channel(-1003L, null));

        // chatId must equal senderId (ADMIN_USER_ID) for private chat validation
        Optional<String> response = commandService
                .processCommand(ADMIN_USER_ID, 1L, ADMIN_USER_ID, "/list_channels")
                .block();

        assertThat(response).isPresent();
        assertThat(response.get()).isEqualTo("""
                Відомі канали — сторінка 1 з 1 (усього 3):

                - ID: -1001 | Title: alpha reports
                - ID: -1002 | Title: Zeta Channel
                - ID: -1003 | Title: (без назви)""");
    }

    @Test
    void commandsAreRejectedInNonPrivateChat() {
        channels.clear();

        // Command from admin but in a group chat (chatId != senderId) should be rejected
        Optional<String> response = commandService
                .processCommand(-1001234567890L, 1L, ADMIN_USER_ID, "/list_channels")
                .block();

        assertThat(response).isEmpty();
    }

    @Test
    void commandsAreRejectedFromNonAdmin() {
        channels.clear();

        // Command from non-admin user should be rejected
        Optional<String> response = commandService
                .processCommand(999L, 1L, 999L, "/list_channels")
                .block();

        assertThat(response).isEmpty();
    }

    private Channel channel(long chatId, String title) {
        Channel channel = new Channel();
        channel.setChatId(chatId);
        channel.setTitle(title);
        return channel;
    }

    private List<Channel> sortedChannels() {
        return channels.stream()
                .sorted((c1, c2) -> {
                    String t1 = c1.getTitle();
                    String t2 = c2.getTitle();
                    if (t1 == null && t2 == null) {
                        return c1.getChatId().compareTo(c2.getChatId());
                    }
                    if (t1 == null) {
                        return 1;
                    }
                    if (t2 == null) {
                        return -1;
                    }
                    int compare = t1.compareToIgnoreCase(t2);
                    return compare != 0 ? compare : c1.getChatId().compareTo(c2.getChatId());
                })
                .toList();
    }

    private class ChannelRepositoryInvocationHandler implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            return switch (method.getName()) {
                case "findAllOrderedForInstance" -> Flux.fromIterable(sortedChannels());
                case "findByChatId" -> Mono.justOrEmpty(
                        channels.stream()
                                .filter(c -> Objects.equals(c.getChatId(), args[0]))
                                .findFirst());
                case "findByTitle" -> Mono.justOrEmpty(
                        channels.stream()
                                .filter(c -> Objects.equals(c.getTitle(), args[0]))
                                .findFirst());
                case "findUnconfiguredHighScoringChannels" -> Flux.empty();
                case "findChannelsNeedingIngestion" -> Flux.fromIterable(channels);
                case "countForInstance" -> Mono.just((long) channels.size());
                default -> {
                    if (method.getDeclaringClass() == Object.class) {
                        yield method.invoke(this, args);
                    }
                    throw new UnsupportedOperationException("Method not supported: " + method.getName());
                }
            };
        }
    }
}
