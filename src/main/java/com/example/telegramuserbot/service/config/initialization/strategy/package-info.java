/**
 * Strategy Pattern implementations for conditional template application.
 *
 * <p>This package contains strategies that determine when to apply specific
 * configuration templates to linked channel-discussion pairs. Each strategy
 * encapsulates conditions and business logic for template selection.
 *
 * <h2>Core Interface</h2>
 * <ul>
 *   <li>{@link com.example.telegramuserbot.service.config.initialization.strategy.TemplateApplicationStrategy}
 *       - Strategy interface defining contract for template application</li>
 * </ul>
 *
 * <h2>Available Strategies</h2>
 * <ul>
 *   <li><b>MinimalReactionStrategy</b> (priority 100) - Russian channels
 *       <ul>
 *         <li>Condition: Cyrillic characters detected in channel metadata</li>
 *         <li>Template: Minimal reaction configuration</li>
 *       </ul>
 *   </li>
 *   <li><b>DefaultStrategy</b> (priority -100) - Fallback (disabled)
 *       <ul>
 *         <li>Condition: Always false (example for future use)</li>
 *         <li>Template: Minimal reaction configuration</li>
 *       </ul>
 *   </li>
 * </ul>
 *
 * <h2>Strategy Evaluation</h2>
 * <p>Strategies are evaluated in priority order (highest first):
 * <ol>
 *   <li>Sort strategies by priority</li>
 *   <li>Evaluate {@code shouldApply(channel, discussion)}</li>
 *   <li>Return first matching strategy</li>
 *   <li>Apply strategy's template</li>
 * </ol>
 *
 * <h2>Creating New Strategy</h2>
 * <p>Create new {@code @Component} implementing {@link TemplateApplicationStrategy}:
 * <pre>{@code
 * @Component
 * public final class EnglishChannelStrategy implements TemplateApplicationStrategy {
 *
 *     @Override
 *     public Mono<Boolean> shouldApply(Channel channel, Channel discussion) {
 *         return Mono.just(isEnglishLanguage(channel));
 *     }
 *
 *     @Override
 *     public LinkedChatsTemplate template() {
 *         return LinkedChatsTemplateFactory.moderateReactionTemplate();
 *     }
 *
 *     @Override
 *     public String name() {
 *         return "moderate-reaction-english";
 *     }
 *
 *     @Override
 *     public int priority() {
 *         return 90; // Lower than Russian strategy
 *     }
 *
 *     private boolean isEnglishLanguage(Channel channel) {
 *         String text = channel.getTitle() + " " + channel.getDescription();
 *         return text.matches(".*[a-zA-Z]+.*");
 *     }
 * }
 * }</pre>
 *
 * <h2>Spring Auto-Discovery</h2>
 * <p>All {@code @Component} strategies are automatically discovered and injected
 * into {@link com.example.telegramuserbot.service.config.initialization.LinkedChatsConfigurationService}.
 * No additional configuration needed!
 *
 * @see com.example.telegramuserbot.service.config.initialization.template.LinkedChatsTemplate
 * @see com.example.telegramuserbot.service.config.initialization.LinkedChatsConfigurationService
 */
package com.example.telegramuserbot.service.config.initialization.strategy;

import com.example.telegramuserbot.service.config.initialization.strategy.TemplateApplicationStrategy;
