/**
 * Automatic configuration initialization for linked channel-discussion chat pairs.
 *
 * <p>This package provides enterprise-ready infrastructure for detecting and configuring
 * channel-discussion relationships in Telegram. When a channel has a linked discussion group,
 * this system automatically applies standardized configuration templates based on channel
 * characteristics using the Strategy Pattern.
 *
 * <h2>Package Structure</h2>
 * <ul>
 *   <li><b>template/</b> - Configuration templates (domain models)</li>
 *   <li><b>strategy/</b> - Strategy Pattern implementations</li>
 * </ul>
 *
 * <h2>Core Components</h2>
 * <ul>
 *   <li>{@link com.example.telegramuserbot.service.config.initialization.strategy.TemplateApplicationStrategy}
 *       - Strategy interface for conditional template application</li>
 *   <li>{@link com.example.telegramuserbot.service.config.initialization.strategy.MinimalReactionStrategy}
 *       - Strategy for Russian-language channels (Cyrillic detection)</li>
 *   <li>{@link com.example.telegramuserbot.service.config.initialization.template.LinkedChatsTemplate}
 *       - Immutable configuration templates for linked chats</li>
 *   <li>{@link com.example.telegramuserbot.service.config.initialization.template.LinkedChatsTemplateFactory}
 *       - Factory for creating standard configuration templates</li>
 *   <li>{@link com.example.telegramuserbot.service.config.initialization.LinkedChatsConfigurationService}
 *       - Service for discovering and configuring linked chat pairs</li>
 *   <li>{@link com.example.telegramuserbot.service.config.initialization.LinkedChatsConfigurationStartupRunner}
 *       - Startup and scheduled execution component</li>
 * </ul>
 *
 * <h2>Strategy Pattern</h2>
 * <p>The system uses strategies to determine which template to apply:
 * <ul>
 *   <li><b>MinimalReactionStrategy</b> (priority 100): Russian channels (Cyrillic detection)</li>
 *   <li><b>DefaultStrategy</b> (priority -100): Fallback (disabled by default)</li>
 * </ul>
 *
 * <p>Strategies are evaluated by priority (highest first). First matching strategy is applied.
 *
 * <h2>Adding New Strategy</h2>
 * <pre>{@code
 * @Component
 * public final class EnglishModerateReactionStrategy implements TemplateApplicationStrategy {
 *     public Mono<Boolean> shouldApply(Channel channel, Channel discussion) {
 *         return Mono.just(isEnglishLanguage(channel));
 *     }
 *
 *     public LinkedChatsTemplate template() {
 *         return LinkedChatsTemplateFactory.moderateReactionTemplate();
 *     }
 *
 *     public String name() {
 *         return "moderate-reaction-english";
 *     }
 *
 *     public int priority() {
 *         return 90;
 *     }
 * }
 * }</pre>
 *
 * <h2>Usage</h2>
 * <p>The system runs automatically:
 * <ul>
 *   <li>At application startup (after chat discovery, 70 second delay)</li>
 *   <li>Daily at 03:00 UTC via scheduled task</li>
 * </ul>
 *
 * <h2>Example</h2>
 * <pre>{@code
 * // Automatic initialization on startup
 * // LinkedChatsConfigurationStartupRunner handles this
 *
 * // Manual trigger if needed
 * @Autowired
 * private LinkedChatsConfigurationService service;
 *
 * public void configure() {
 *     service.initializeLinkedChatsConfiguration()
 *         .subscribe(count -> log.info("Configured {} pairs", count));
 * }
 * }</pre>
 *
 * <h2>Configuration Pattern</h2>
 * <p>Based on Example configuration:
 * <ul>
 *   <li><b>Channel</b>: Minimal config, bot disabled, sync enabled</li>
 *   <li><b>Discussion</b>: Full AI config with minimal reaction mode</li>
 *   <li><b>Language</b>: Automatically detected (Russian via Cyrillic)</li>
 * </ul>
 *
 * @see com.example.telegramuserbot.service.config.initialization.LinkedChatsConfigurationService
 * @see com.example.telegramuserbot.service.config.initialization.strategy.TemplateApplicationStrategy
 * @see com.example.telegramuserbot.service.config.initialization.strategy.MinimalReactionStrategy
 * @see com.example.telegramuserbot.service.config.initialization.template.LinkedChatsTemplate
 */
package com.example.telegramuserbot.service.config.initialization;
