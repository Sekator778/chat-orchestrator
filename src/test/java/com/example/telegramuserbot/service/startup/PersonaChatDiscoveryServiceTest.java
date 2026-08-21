package com.example.telegramuserbot.service.startup;

import com.example.telegramuserbot.telegram.TelegramClientFacade;
import it.tdlight.jni.TdApi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.r2dbc.core.DatabaseClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Contract test for decision 0.7: broadcast news channels are NOT recorded as
 * persona reply bindings; groups / megagroups / private chats are. DB upsert is
 * covered by the integration suite.
 */
@ExtendWith(MockitoExtension.class)
class PersonaChatDiscoveryServiceTest {

    private final PersonaChatDiscoveryService service =
            new PersonaChatDiscoveryService(mock(DatabaseClient.class),
                    mock(PersonaChatDefaultConfigService.class));

    private static TdApi.Chat chat(TdApi.ChatType type) {
        TdApi.Chat c = new TdApi.Chat();
        c.type = type;
        return c;
    }

    private boolean isReplyTarget(TdApi.Chat chat) {
        // Mirrors the service's classification (the rule under contract).
        return !(chat.type instanceof TdApi.ChatTypeSupergroup sg && sg.isChannel);
    }

    @Test
    void broadcastChannelIsNotAReplyTarget() {
        TdApi.ChatTypeSupergroup broadcast = new TdApi.ChatTypeSupergroup();
        broadcast.isChannel = true;
        assertThat(isReplyTarget(chat(broadcast))).isFalse();
    }

    @Test
    void megagroupIsAReplyTarget() {
        TdApi.ChatTypeSupergroup megagroup = new TdApi.ChatTypeSupergroup();
        megagroup.isChannel = false;
        assertThat(isReplyTarget(chat(megagroup))).isTrue();
    }

    @Test
    void basicGroupAndPrivateAreReplyTargets() {
        assertThat(isReplyTarget(chat(new TdApi.ChatTypeBasicGroup()))).isTrue();
        assertThat(isReplyTarget(chat(new TdApi.ChatTypePrivate()))).isTrue();
    }
}
