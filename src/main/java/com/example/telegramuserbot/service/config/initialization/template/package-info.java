/**
 * Configuration templates for linked channel-discussion chat pairs.
 *
 * <p>This package contains immutable domain models and YAML loading infrastructure
 * for configuration templates. Templates are loaded from YAML files in
 * {@code resources/templates/linked-chats/} directory.
 *
 * <h2>Core Classes</h2>
 * <ul>
 *   <li>{@link com.example.telegramuserbot.service.config.initialization.template.LinkedChatsTemplate}
 *       - Immutable domain model containing all configuration settings</li>
 *   <li>{@link com.example.telegramuserbot.service.config.initialization.template.LinkedChatsTemplateFactory}
 *       - Factory for accessing templates loaded from YAML files</li>
 *   <li>{@link com.example.telegramuserbot.service.config.initialization.template.LinkedChatsTemplateLoader}
 *       - Loads and caches templates from YAML files</li>
 *   <li>{@link com.example.telegramuserbot.service.config.initialization.template.LinkedChatsTemplateYaml}
 *       - YAML deserialization model (internal use only)</li>
 * </ul>
 *
 * <h2>Available Templates</h2>
 * <ul>
 *   <li><b>minimal-reaction.yaml</b> - Simple short reactions (Example style)
 *       <ul>
 *         <li>8 messages per day max</li>
 *         <li>Temperature: 0.35</li>
 *         <li>Tone: CASUAL with light emotion</li>
 *         <li>Based on: config_Example.sql</li>
 *       </ul>
 *   </li>
 *   <li><b>low-engagement-followup.yaml</b> - Neutral follow-up after humans
 *       <ul>
 *         <li>5 messages per day max</li>
 *         <li>Temperature: 0.18</li>
 *         <li>Tone: NEUTRAL, factual</li>
 *         <li>Wait for human replies first</li>
 *         <li>Based on: config_low_engagement_followup.sql</li>
 *       </ul>
 *   </li>
 * </ul>
 *
 * <h2>Template Structure</h2>
 * <p>Each template contains:
 * <ul>
 *   <li><b>ChannelTemplate</b> - Minimal configuration for primary channel</li>
 *   <li><b>DiscussionTemplate</b> - Full AI configuration for discussion group
 *       <ul>
 *         <li>ContextSettingsTemplate</li>
 *         <li>LlmParametersTemplate</li>
 *         <li>RateLimitsTemplate</li>
 *         <li>ResponseTemplateConfig</li>
 *         <li>TriggerConditionConfig</li>
 *       </ul>
 *   </li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * @Autowired
 * private LinkedChatsTemplateFactory factory;
 *
 * // Load by convenience method
 * LinkedChatsTemplate template = factory.minimalReactionTemplate();
 *
 * // Or load by name
 * LinkedChatsTemplate template = factory.loadTemplate("linked-chats/minimal-reaction");
 *
 * LinkedChatsTemplate.ChannelTemplate channel = template.channel();
 * LinkedChatsTemplate.DiscussionTemplate discussion = template.discussion();
 * }</pre>
 *
 * <h2>Creating New Template</h2>
 * <p>Create new YAML file in {@code resources/templates/linked-chats/}:
 * <pre>{@code
 * # my-template.yaml
 * templateName: my-template
 * description: My configuration description
 *
 * channel:
 *   enabled: false
 *   maxDailyMessages: 10
 *   # ...
 *
 * discussion:
 *   enabled: true
 *   maxDailyMessages: 5
 *   # ...
 * }</pre>
 *
 * <p>Optionally add convenience method to {@link LinkedChatsTemplateFactory}:
 * <pre>{@code
 * public LinkedChatsTemplate myTemplate() {
 *     return loader.load("my-template");
 * }
 * }</pre>
 *
 * @see com.example.telegramuserbot.service.config.initialization.strategy.TemplateApplicationStrategy
 * @see com.example.telegramuserbot.service.config.initialization.LinkedChatsConfigurationService
 */
package com.example.telegramuserbot.service.config.initialization.template;
