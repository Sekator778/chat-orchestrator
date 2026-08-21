package com.example.telegramuserbot.service;

import com.example.telegramuserbot.domain.CommunicationStyle;
import com.example.telegramuserbot.domain.ResponseLength;
import com.example.telegramuserbot.domain.User;
import com.example.telegramuserbot.repository.UserRepository;
import it.tdlight.jni.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import com.example.telegramuserbot.dto.UserUpdateDto;

@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public Mono<User> getOrCreateUser(TdApi.MessageSender sender) {
        if (sender instanceof TdApi.MessageSenderUser userSender) {
            return getOrCreateUser(userSender.userId, null, null, null);
        }
        return Mono.empty();
    }

    public Mono<User> getOrCreateUser(Long telegramUserId, String firstName, String lastName, String username) {
        return userRepository.findByTelegramUserId(telegramUserId)
                .flatMap(user -> {
                    user.setLastInteractionAt(Instant.now());
                    boolean updated = false;
                    if (firstName != null && !firstName.equals(user.getFirstName())) {
                        user.setFirstName(firstName);
                        updated = true;
                    }
                    if (lastName != null && !lastName.equals(user.getLastName())) {
                        user.setLastName(lastName);
                        updated = true;
                    }
                    if (username != null && !username.equals(user.getUsername())) {
                        user.setUsername(username);
                        updated = true;
                    }
                    if (updated) {
                        log.debug("Updated user info for telegramUserId: {}", telegramUserId);
                    }
                    return userRepository.save(user);
                })
                .switchIfEmpty(Mono.defer(() -> {
                    User newUser = new User(telegramUserId, firstName, lastName, username);
                    newUser.setCommunicationStyle(CommunicationStyle.CASUAL);
                    newUser.setResponseLength(ResponseLength.MEDIUM);
                    newUser.setLanguagePreference("uk");
                    newUser.setAiEnabled(true);
                    log.info("Created new user with telegramUserId: {}, name: {} {}", telegramUserId, firstName, lastName);
                    return userRepository.save(newUser);
                }));
    }

    public Mono<Void> updateLastInteraction(Long telegramUserId) {
        return userRepository.updateLastInteractionTime(telegramUserId, Instant.now()).then();
    }

    public Mono<Boolean> isAiEnabledForUser(Long telegramUserId) {
        return userRepository.isAiEnabledForUser(telegramUserId).defaultIfEmpty(false);
    }

    public Mono<User> getUserByTelegramId(Long telegramUserId) {
        return userRepository.findByTelegramUserId(telegramUserId);
    }

    public String buildPersonalizedPrompt(User user, String basePrompt) {
        if (user == null) {
            return basePrompt;
        }

        StringBuilder personalizedPrompt = new StringBuilder();

        if (basePrompt != null && !basePrompt.isBlank()) {
            personalizedPrompt.append(basePrompt).append("\n\n");
        }

        personalizedPrompt.append("ПЕРСОНАЛІЗАЦІЯ СПІЛКУВАННЯ:\n");

        String addressName = user.getDisplayName();
        if (user.getPreferredTitle() != null && !user.getPreferredTitle().isBlank()) {
            addressName = user.getPreferredTitle() + " " + addressName;
        }
        personalizedPrompt.append("- Звертайся до співрозмовника як: ").append(addressName).append("\n");

        personalizedPrompt.append("- Стиль спілкування: ").append(user.getCommunicationStyle().getDescription()).append("\n");

        personalizedPrompt.append("- Довжина відповідей: ").append(user.getResponseLength().getDescription()).append("\n");

        personalizedPrompt.append("- Мова спілкування: ");
        switch (user.getLanguagePreference()) {
            case "uk" -> personalizedPrompt.append("українська");
            case "ru" -> personalizedPrompt.append("російська");
            case "en" -> personalizedPrompt.append("англійська");
            case "auto" -> personalizedPrompt.append("автовизначення (відповідай мовою запитання)");
            default -> personalizedPrompt.append("українська (за замовчуванням)");
        }
        personalizedPrompt.append("\n");

        if (user.getPersonalityTraits() != null && !user.getPersonalityTraits().isBlank()) {
            personalizedPrompt.append("- Особливості особистості співрозмовника: ").append(user.getPersonalityTraits()).append("\n");
        }

        if (user.getRelationshipContext() != null && !user.getRelationshipContext().isBlank()) {
            personalizedPrompt.append("- Контекст стосунків: ").append(user.getRelationshipContext()).append("\n");
        }

        personalizedPrompt.append("\nЗавжди враховуй ці персональні налаштування у своїх відповідях.");

        return personalizedPrompt.toString();
    }

    /**
     * Принимает Mono<User> и применяет к нему синхронную логику персонализации.
     * Этот метод будет вызываться из EnhancedLlmService.
     */
    public Mono<String> buildPersonalizedPrompt(Mono<User> userMono, String basePrompt) {
        return userMono
                .map(user -> buildPersonalizedPrompt(user, basePrompt)) // Применяем синхронную логику внутри .map()
                .defaultIfEmpty(basePrompt); // Если пользователь не найден (Mono пустой), возвращаем базовый промпт
    }

    public Mono<User> saveUser(User user) {
        return userRepository.save(user);
    }

    public Flux<User> getAllUsers() {
        return userRepository.findAll();
    }

    public Mono<User> updateUser(Long telegramUserId, UserUpdateDto updateDto) {
        return userRepository.findByTelegramUserId(telegramUserId)
                .flatMap(user -> {
                    if (updateDto.preferredName() != null) {
                        user.setPreferredName(updateDto.preferredName());
                    }
                    if (updateDto.preferredTitle() != null) {
                        user.setPreferredTitle(updateDto.preferredTitle());
                    }
                    if (updateDto.communicationStyle() != null) {
                        user.setCommunicationStyle(updateDto.communicationStyle());
                    }
                    if (updateDto.personalityTraits() != null) {
                        user.setPersonalityTraits(updateDto.personalityTraits());
                    }
                    if (updateDto.relationshipContext() != null) {
                        user.setRelationshipContext(updateDto.relationshipContext());
                    }
                    if (updateDto.languagePreference() != null) {
                        user.setLanguagePreference(updateDto.languagePreference());
                    }
                    if (updateDto.responseLength() != null) {
                        user.setResponseLength(updateDto.responseLength());
                    }
                    if (updateDto.aiEnabled() != null) {
                        user.setAiEnabled(updateDto.aiEnabled());
                    }
                    log.info("Updated user {} with fields: {}", telegramUserId, updateDto.hasUpdates() ? "multiple fields" : "no changes");
                    return userRepository.save(user);
                })
                .switchIfEmpty(Mono.error(new IllegalArgumentException("User with telegram ID " + telegramUserId + " not found")));
    }

    public Mono<Void> deleteUser(User user) {
        return userRepository.delete(user);
    }
}