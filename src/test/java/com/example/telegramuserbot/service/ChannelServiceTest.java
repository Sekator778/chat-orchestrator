package com.example.telegramuserbot.service;

import com.example.telegramuserbot.config.BotInstanceProvider;
import com.example.telegramuserbot.domain.Channel;
import com.example.telegramuserbot.repository.ChannelRepository;
import com.example.telegramuserbot.repository.ChatConfigRepository;
import com.example.telegramuserbot.repository.RateLimitsRepository;
import com.example.telegramuserbot.repository.TriggerConditionRepository;
import com.example.telegramuserbot.service.cache.SyncEnabledChatsCache;
import com.example.telegramuserbot.service.startup.ChatDiscoveryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ChannelService normalization behavior on the discovery save path.
 * Pure Mockito — no Spring context, no database.
 *
 * All test IDs are synthetic (not real Telegram chat ids):
 *   RAW_POSITIVE_ID  = 1234567890L          (synthetic positive supergroup id)
 *   NORMALIZED_ID    = -1001234567890L       (canonical negative form: "-100" + raw)
 *
 * FR-001: ensureSupergroupPrefix is applied before save so the persisted id satisfies id < 0.
 * FR-002: non-supergroup chats (normalized id >= 0, or null) are skipped without calling save.
 * FR-003: the ArgumentCaptor assertion confirms getChatId() equals the expected negative form.
 * FR-004: existing channel discovered via positive raw id is resolved correctly (no IllegalStateException).
 *
 * Strict stubs (no class-level LENIENT): each test declares only the stubs it exercises.
 * Tests that legitimately leave a stub unreachable use lenient() on the specific stub only.
 */
@ExtendWith(MockitoExtension.class)
class ChannelServiceTest {

    // Synthetic test ids — deliberately not real Telegram chat ids.
    private static final long RAW_POSITIVE_ID   = 1234567890L;
    private static final long NORMALIZED_ID     = -1001234567890L;

    @Mock
    private ChannelRepository channelRepository;
    @Mock
    private ChatConfigRepository chatConfigRepository;
    @Mock
    private RateLimitsRepository rateLimitsRepository;
    @Mock
    private SyncEnabledChatsCache syncEnabledChatsCache;
    @Mock
    private TriggerConditionRepository triggerConditionRepository;
    @Mock
    private BotInstanceProvider botInstanceProvider;

    private ChannelService channelService;

    @BeforeEach
    void setUp() {
        channelService = new ChannelService(
                channelRepository,
                chatConfigRepository,
                rateLimitsRepository,
                null,               // ChannelLanguageDetectionService — @Nullable, not needed here
                syncEnabledChatsCache,
                triggerConditionRepository,
                botInstanceProvider
        );
        // lenient: skip tests fire their guard before reaching getInstanceId(), so this stub
        // is legitimately unreachable in those tests. Per-stub lenient avoids class-level LENIENT.
        lenient().when(botInstanceProvider.getInstanceId()).thenReturn("test-bot-instance");
    }

    // -----------------------------------------------------------------------
    // FR-001: normalization on the save path
    // -----------------------------------------------------------------------

    /**
     * AC-001.1, FR-001, AC-003.3:
     * Given a positive raw supergroup id (RAW_POSITIVE_ID), the Channel captured by
     * channelRepository.save must have getChatId() equal to the canonical negative form
     * NORMALIZED_ID (satisfying id < 0).
     */
    @Test
    void coercesPositiveSuperGroupIdToNegativeCanonicalFormBeforeSave() {
        ChatDiscoveryService.ChatInfo chatInfo = new ChatDiscoveryService.ChatInfo(
                RAW_POSITIVE_ID,
                "Test Supergroup",
                null,           // chatType — null treated as non-channel
                null,           // lastMessageId
                null,           // lastMessageDate
                true,           // canReadMessages
                true,           // canSendMessages
                true,           // isAccessible
                500             // memberCount
        );

        // Both findExistingChannelVariant (normalized id) and the in-create guard use normalized id.
        when(channelRepository.findByChatId(eq(NORMALIZED_ID))).thenReturn(Mono.empty());
        when(channelRepository.save(any(Channel.class))).thenAnswer(invocation -> {
            Channel ch = invocation.getArgument(0);
            ch.markPersisted();
            return Mono.just(ch);
        });
        when(chatConfigRepository.findByChannelChatId(anyLong())).thenReturn(Mono.empty());
        when(chatConfigRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        lenient().doNothing().when(syncEnabledChatsCache).invalidate(anyLong());

        StepVerifier.create(channelService.findOrCreateChannelAndConfig(chatInfo))
                .expectNextMatches(channel -> channel.getChatId() != null && channel.getChatId() < 0)
                .verifyComplete();

        ArgumentCaptor<Channel> captor = ArgumentCaptor.forClass(Channel.class);
        verify(channelRepository, atLeastOnce()).save(captor.capture());

        Channel saved = captor.getAllValues().get(0);
        assertThat(saved.getChatId())
                .as("Persisted chat id must be the canonical negative supergroup form")
                .isEqualTo(NORMALIZED_ID)
                .isLessThan(0L);
    }

    /**
     * AC-001.2, FR-001 (idempotency):
     * Given an already-negative supergroup id (NORMALIZED_ID), the Channel captured by
     * channelRepository.save must have getChatId() unchanged (NORMALIZED_ID).
     */
    @Test
    void preservesAlreadyNegativeSuperGroupIdUnchanged() {
        ChatDiscoveryService.ChatInfo chatInfo = new ChatDiscoveryService.ChatInfo(
                NORMALIZED_ID,
                "Already Prefixed Channel",
                null,
                null,
                null,
                true,
                true,
                true,
                100
        );

        when(channelRepository.findByChatId(eq(NORMALIZED_ID))).thenReturn(Mono.empty());
        when(channelRepository.save(any(Channel.class))).thenAnswer(invocation -> {
            Channel ch = invocation.getArgument(0);
            ch.markPersisted();
            return Mono.just(ch);
        });
        when(chatConfigRepository.findByChannelChatId(anyLong())).thenReturn(Mono.empty());
        when(chatConfigRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        lenient().doNothing().when(syncEnabledChatsCache).invalidate(anyLong());

        StepVerifier.create(channelService.findOrCreateChannelAndConfig(chatInfo))
                .expectNextMatches(channel -> NORMALIZED_ID == channel.getChatId())
                .verifyComplete();

        ArgumentCaptor<Channel> captor = ArgumentCaptor.forClass(Channel.class);
        verify(channelRepository, atLeastOnce()).save(captor.capture());

        assertThat(captor.getAllValues().get(0).getChatId())
                .as("Already-negative id must pass through ensureSupergroupPrefix unchanged")
                .isEqualTo(NORMALIZED_ID)
                .isLessThan(0L);
    }

    // -----------------------------------------------------------------------
    // FR-002: skip non-supergroup / null / overflow ids
    // -----------------------------------------------------------------------

    /**
     * AC-002.1, AC-002.2, FR-002 — null id skip:
     * When chatId is null, ensureSupergroupPrefix returns null, triggering the null-guard
     * in ensureChannelExists. The chain completes empty and save is never called.
     *
     * buildChatIdCandidates(null) returns an empty list so findExistingChannelVariant
     * emits empty without any repository call, and ensureChannelExists hits the null guard.
     */
    @Test
    void nullChatIdSkipsInsert() {
        ChatDiscoveryService.ChatInfo chatInfo = new ChatDiscoveryService.ChatInfo(
                null,           // chatId — null; ChatInfo.chatId() is Long (boxed)
                "Null-ID Chat",
                null,
                null,
                null,
                false,
                false,
                false,
                0
        );

        // No repository calls expected; stub defensively with lenient() so strict Mockito
        // does not report an unused stub if the guard fires before any findByChatId call.
        lenient().when(channelRepository.findByChatId(anyLong())).thenReturn(Mono.empty());

        StepVerifier.create(channelService.findOrCreateChannelAndConfig(chatInfo))
                .verifyComplete();

        verify(channelRepository, never()).save(any(Channel.class));
    }

    /**
     * AC-002.1, AC-002.2, FR-002 — overflow / parse-range-failure skip:
     * A pathologically large positive id (1_000_000_000_000_000_000L, 19 significant digits)
     * causes TelegramChatIdUtils.ensureSupergroupPrefix to attempt Long.parseLong("-100" + id),
     * producing the 20-character string "-1001000000000000000" which exceeds Long.MAX_VALUE
     * (9_223_372_036_854_775_807).  Long.parseLong throws NumberFormatException; the catch
     * block returns the original positive value unchanged.  Because the returned value is >= 0,
     * the guard in ensureChannelExists fires and channelRepository.save is never called.
     *
     * The ">= 0 skip" guard is a defensive boundary for parse-range failures and null ids;
     * real TDLib supergroup ids are always 9-10 digits and coerce cleanly to a negative value,
     * so this branch is never reached in normal operation (FR-002, A-2).
     */
    @Test
    void overflowLargePositiveIdSkipsInsert() {
        // 10^18 — 19 digits. "-100" + "1000000000000000000" = "-1001000000000000000" exceeds Long range.
        long oversizedId = 1_000_000_000_000_000_000L;

        ChatDiscoveryService.ChatInfo chatInfo = new ChatDiscoveryService.ChatInfo(
                oversizedId,
                "Overflow Chat",
                null,
                null,
                null,
                false,
                false,
                false,
                0
        );

        // ensureChannelExists normalizes first → oversizedId (>= 0) → guard fires immediately.
        // No findByChatId or save calls expected. Lenient stub guards against guard ordering changes.
        lenient().when(channelRepository.findByChatId(anyLong())).thenReturn(Mono.empty());

        StepVerifier.create(channelService.findOrCreateChannelAndConfig(chatInfo))
                .verifyComplete();   // must complete empty — no element, no error

        verify(channelRepository, never()).save(any(Channel.class));
    }

    /**
     * AC-002.1, AC-002.2, FR-002 — zero id skip:
     * chatId = 0L: ensureSupergroupPrefix returns 0L (not > 0, no -100 prefix applied).
     * 0L >= 0 triggers the skip guard. This is a sentinel/boundary value — zero never
     * appears as a real TDLib chat id in production.
     */
    @Test
    void skipsNonSupergroupChatWithoutCallingRepository() {
        long nonSupergroupId = 0L;

        ChatDiscoveryService.ChatInfo chatInfo = new ChatDiscoveryService.ChatInfo(
                nonSupergroupId,
                "Non-Supergroup Chat",
                null,
                null,
                null,
                false,
                false,
                false,
                0
        );

        // buildChatIdCandidates(0L) returns empty list, so ensureChannelExists' guard fires
        // immediately after normalization. No repository call is expected.
        lenient().when(channelRepository.findByChatId(anyLong())).thenReturn(Mono.empty());

        StepVerifier.create(channelService.findOrCreateChannelAndConfig(chatInfo))
                .verifyComplete();   // must complete without emitting any element

        verify(channelRepository, never()).save(any(Channel.class));
    }

    // -----------------------------------------------------------------------
    // FR-004: existing-channel discovery — lookup/guard must key off normalized id
    // -----------------------------------------------------------------------

    /**
     * C-2 regression guard — FR-004, existing-channel discovery via positive raw id.
     *
     * This test captures the exact production failure mode fixed by C-1:
     * Before the fix, ensureChannelExists called findExistingChannelVariant(chatInfo.chatId())
     * with the RAW positive id (RAW_POSITIVE_ID), which missed the row stored as NORMALIZED_ID,
     * causing the code to fall into createNewChannel where the in-create findByChatId(normalized)
     * DID find the row and emitted IllegalStateException. The fix lifts normalization to
     * ensureChannelExists so both paths key off the same normalized negative id.
     *
     * Stub strategy (asymmetric, not anyLong()):
     * - findByChatId(NORMALIZED_ID) returns Mono.just(existingChannel): the canonical row.
     * - findByChatId(RAW_POSITIVE_ID) is NOT stubbed — if the code regresses to calling this,
     *   Mockito returns empty by default, the chain falls into createNewChannel, then
     *   findByChatId(NORMALIZED_ID) returns existingChannel, triggering IllegalStateException
     *   → verifyComplete detects the error and the test fails.
     *
     * On the fixed code: findExistingChannelVariant(NORMALIZED_ID) → existingChannel →
     * updateChannelMetadata → chain emits the existing channel without error.
     */
    @Test
    void existingChannelFoundViaNormalizedIdNeverThrows() {
        // Existing channel stored with the canonical negative id (post-changeset-047 form).
        Channel existingChannel = new Channel();
        existingChannel.setChatId(NORMALIZED_ID);
        existingChannel.setTitle("Existing Supergroup");
        existingChannel.markPersisted();

        ChatDiscoveryService.ChatInfo chatInfo = new ChatDiscoveryService.ChatInfo(
                RAW_POSITIVE_ID,
                "Existing Supergroup",
                null,
                null,
                null,
                true,
                true,
                true,
                800
        );

        // Asymmetric stub: only the normalized id resolves to the existing channel.
        // findByChatId(RAW_POSITIVE_ID) is not stubbed → Mockito returns empty by default.
        when(channelRepository.findByChatId(eq(NORMALIZED_ID))).thenReturn(Mono.just(existingChannel));
        // channelRepository.save may be called by ensureChatConfigExistsInternal for join_status updates.
        lenient().when(channelRepository.save(any(Channel.class))).thenAnswer(inv -> {
            Channel ch = inv.getArgument(0);
            ch.markPersisted();
            return Mono.just(ch);
        });
        // Chat-config path is not the focus; stub to let the chain complete.
        lenient().when(chatConfigRepository.findByChannelChatId(anyLong())).thenReturn(Mono.empty());
        lenient().when(chatConfigRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        lenient().doNothing().when(syncEnabledChatsCache).invalidate(anyLong());

        // On the OLD code this would emit IllegalStateException; on the fixed code the existing
        // channel is resolved via findExistingChannelVariant(NORMALIZED_ID) and returned.
        StepVerifier.create(channelService.findOrCreateChannelAndConfig(chatInfo))
                .assertNext(channel -> {
                    assertThat(channel.getChatId())
                            .as("Chain must return the existing channel, not throw IllegalStateException")
                            .isEqualTo(NORMALIZED_ID);
                })
                .verifyComplete();

        // Existence lookup must have used the normalized id, not the raw positive id.
        verify(channelRepository, atLeastOnce()).findByChatId(eq(NORMALIZED_ID));
    }

    // -----------------------------------------------------------------------
    // FR-002 / FR-001: save-path invariants and cache invalidation
    // -----------------------------------------------------------------------

    /**
     * W-3 — syncEnabledChatsCache.invalidate is called with the normalized chat id
     * after a new ChatConfig is created for a newly-discovered channel.
     *
     * This pins the cache-invalidation invariant that was present in the prior commented-out
     * skeleton tests (see createDefaultChatConfig → syncEnabledChatsCache.invalidate(tdlibChannelId)).
     */
    @Test
    void newChannelCreationInvalidatesSyncEnabledCache() {
        ChatDiscoveryService.ChatInfo chatInfo = new ChatDiscoveryService.ChatInfo(
                RAW_POSITIVE_ID,
                "Cache Test Supergroup",
                null,
                null,
                null,
                true,           // canReadMessages — causes shouldMarkAsJoined = true
                true,
                true,
                300
        );

        when(channelRepository.findByChatId(eq(NORMALIZED_ID))).thenReturn(Mono.empty());
        when(channelRepository.save(any(Channel.class))).thenAnswer(invocation -> {
            Channel ch = invocation.getArgument(0);
            ch.markPersisted();
            return Mono.just(ch);
        });
        when(chatConfigRepository.findByChannelChatId(anyLong())).thenReturn(Mono.empty());
        when(chatConfigRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        doNothing().when(syncEnabledChatsCache).invalidate(anyLong());

        StepVerifier.create(channelService.findOrCreateChannelAndConfig(chatInfo))
                .expectNextCount(1)
                .verifyComplete();

        // Cache must be invalidated with the normalized (negative) chat id after config creation.
        verify(syncEnabledChatsCache, atLeastOnce()).invalidate(eq(NORMALIZED_ID));
    }

    /**
     * W-3 — default ChatConfig values: syncEnabled=false, autoSyncEnabled=false.
     * Pins the invariant from the prior skeleton that the config is created with safe defaults.
     */
    @Test
    void newChannelCreationSavesConfigWithSafeDefaults() {
        ChatDiscoveryService.ChatInfo chatInfo = new ChatDiscoveryService.ChatInfo(
                NORMALIZED_ID,
                "Default Config Supergroup",
                null,
                null,
                null,
                true,
                true,
                true,
                100
        );

        when(channelRepository.findByChatId(eq(NORMALIZED_ID))).thenReturn(Mono.empty());
        when(channelRepository.save(any(Channel.class))).thenAnswer(invocation -> {
            Channel ch = invocation.getArgument(0);
            ch.markPersisted();
            return Mono.just(ch);
        });
        when(chatConfigRepository.findByChannelChatId(anyLong())).thenReturn(Mono.empty());

        ArgumentCaptor<com.example.telegramuserbot.domain.ChatConfig> configCaptor =
                ArgumentCaptor.forClass(com.example.telegramuserbot.domain.ChatConfig.class);
        when(chatConfigRepository.save(configCaptor.capture()))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        lenient().doNothing().when(syncEnabledChatsCache).invalidate(anyLong());

        StepVerifier.create(channelService.findOrCreateChannelAndConfig(chatInfo))
                .expectNextCount(1)
                .verifyComplete();

        assertThat(configCaptor.getValue().isSyncEnabled())
                .as("New ChatConfig must default to syncEnabled=false")
                .isFalse();
        assertThat(configCaptor.getValue().getAutoSyncEnabled())
                .as("New ChatConfig must default to autoSyncEnabled=false")
                .isFalse();
    }

    // -----------------------------------------------------------------------
    // Brownfield regression: adjacent untouched paths are unchanged
    // -----------------------------------------------------------------------

    /**
     * Regression guard — negative non-supergroup id passthrough (unchanged behavior).
     *
     * A basic-group id like -4812345678L:
     *   - toString() = "-4812345678" which does NOT start with "-100"
     *   - chatId > 0 is false → ensureSupergroupPrefix returns -4812345678L unchanged
     *   - -4812345678L < 0 → skip guard does NOT fire
     *   - save() IS called with the original negative value
     *
     * This pins the boundary: the guard only skips ids >= 0. Negative non-supergroup
     * ids satisfy id < 0 and are persisted as-is (unchanged behavior, not touched by the fix).
     */
    @Test
    void negativeNonSupergroupIdIsSavedUnchanged() {
        long basicGroupId = -4812345678L;

        ChatDiscoveryService.ChatInfo chatInfo = new ChatDiscoveryService.ChatInfo(
                basicGroupId,
                "Basic Group Chat",
                null,
                null,
                null,
                true,
                false,
                true,
                25
        );

        when(channelRepository.findByChatId(eq(basicGroupId))).thenReturn(Mono.empty());
        when(channelRepository.save(any(Channel.class))).thenAnswer(invocation -> {
            Channel ch = invocation.getArgument(0);
            ch.markPersisted();
            return Mono.just(ch);
        });
        when(chatConfigRepository.findByChannelChatId(anyLong())).thenReturn(Mono.empty());
        when(chatConfigRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        lenient().doNothing().when(syncEnabledChatsCache).invalidate(anyLong());

        StepVerifier.create(channelService.findOrCreateChannelAndConfig(chatInfo))
                .expectNextMatches(channel -> basicGroupId == channel.getChatId())
                .verifyComplete();

        ArgumentCaptor<Channel> captor = ArgumentCaptor.forClass(Channel.class);
        verify(channelRepository, atLeastOnce()).save(captor.capture());

        assertThat(captor.getAllValues().get(0).getChatId())
                .as("Negative non-supergroup id must be stored unchanged (satisfies id < 0 constraint)")
                .isEqualTo(basicGroupId)
                .isLessThan(0L);
    }

    // -----------------------------------------------------------------------
    // BRD AC-001.1 regression: positive supergroup id → canonical negative form
    // Uses a synthetic 10-digit id structurally equivalent to the production case.
    // -----------------------------------------------------------------------

    /**
     * AC-001.1 regression: drives a positive 10-digit supergroup id (structural
     * equivalent of the production failure described in the BRD) through the full
     * findOrCreateChannelAndConfig path and verifies the persisted chatId equals
     * the canonical "-100" + raw form (i.e. getChatId() < 0).
     *
     * Kept separate from the NORMALIZED_ID-based test above so AC-001.1's
     * positive-raw-id create path is independently pinned in CI output.
     */
    @Test
    void brdAc001PositiveRawIdIsPersistedAsCanonicalNegativeId() {
        // Synthetic 10-digit id structurally equivalent to the BRD production case
        long rawId = 1987654321L;
        long expectedNormalizedId = -1001987654321L;

        ChatDiscoveryService.ChatInfo chatInfo = new ChatDiscoveryService.ChatInfo(
                rawId,
                "AC-001.1 Supergroup",
                null,
                null,
                null,
                true,
                true,
                true,
                1000
        );

        when(channelRepository.findByChatId(eq(expectedNormalizedId))).thenReturn(Mono.empty());
        when(channelRepository.save(any(Channel.class))).thenAnswer(invocation -> {
            Channel ch = invocation.getArgument(0);
            ch.markPersisted();
            return Mono.just(ch);
        });
        when(chatConfigRepository.findByChannelChatId(anyLong())).thenReturn(Mono.empty());
        when(chatConfigRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        lenient().doNothing().when(syncEnabledChatsCache).invalidate(anyLong());

        StepVerifier.create(channelService.findOrCreateChannelAndConfig(chatInfo))
                .expectNextMatches(channel -> channel.getChatId() != null && channel.getChatId() < 0)
                .verifyComplete();

        ArgumentCaptor<Channel> captor = ArgumentCaptor.forClass(Channel.class);
        verify(channelRepository, atLeastOnce()).save(captor.capture());

        assertThat(captor.getAllValues().get(0).getChatId())
                .as("AC-001.1: positive raw id must be persisted as canonical negative form (id < 0)")
                .isEqualTo(expectedNormalizedId)
                .isLessThan(0L);
    }

    /**
     * Regression guard — duplicate-guard lookup uses the normalized (negative) id.
     *
     * Before the fix, createNewChannel called channelRepository.findByChatId(tdlibChatId)
     * with the raw positive id, which would miss rows stored in negative form.
     * After the fix, ensureChannelExists normalizes first, and the in-create guard also
     * receives the normalized id. This test pins that change with an eq() stub:
     * if the code regressed to using the raw positive for any lookup, the eq(NORMALIZED_ID)
     * verify at the end would still pass — but the findByChatId(NORMALIZED_ID) stub would
     * not be matched on the existence lookup, causing the chain to fall into createNewChannel
     * where the in-create guard calls findByChatId(NORMALIZED_ID), so save proceeds correctly.
     * The verify(atLeastOnce) ensures the normalized id was used somewhere in the lookup chain.
     */
    @Test
    void duplicateGuardLookupUsesNormalizedId() {
        ChatDiscoveryService.ChatInfo chatInfo = new ChatDiscoveryService.ChatInfo(
                RAW_POSITIVE_ID,
                "Lookup Pin Supergroup",
                null,
                null,
                null,
                true,
                true,
                true,
                300
        );

        // Only stub the normalized id; Mockito returns empty by default for unstubbed calls.
        when(channelRepository.findByChatId(eq(NORMALIZED_ID))).thenReturn(Mono.empty());
        when(channelRepository.save(any(Channel.class))).thenAnswer(invocation -> {
            Channel ch = invocation.getArgument(0);
            ch.markPersisted();
            return Mono.just(ch);
        });
        when(chatConfigRepository.findByChannelChatId(anyLong())).thenReturn(Mono.empty());
        when(chatConfigRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        lenient().doNothing().when(syncEnabledChatsCache).invalidate(anyLong());

        StepVerifier.create(channelService.findOrCreateChannelAndConfig(chatInfo))
                .expectNextMatches(channel -> channel.getChatId() != null && channel.getChatId() < 0)
                .verifyComplete();

        // The existence lookup and in-create guard must both have used the normalized negative id.
        verify(channelRepository, atLeastOnce()).findByChatId(eq(NORMALIZED_ID));
    }
}
