package com.example.telegramuserbot.service.humanization.helper;

import com.example.telegramuserbot.domain.CommunicationStyle;
import com.example.telegramuserbot.domain.ResponseLength;
import com.example.telegramuserbot.domain.User;
import com.example.telegramuserbot.domain.UserCommunicationProfile;
import com.example.telegramuserbot.repository.UserCommunicationProfileRepository;
import com.example.telegramuserbot.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Optional;

@Component
public class UserProfileManager {

    private static final Logger log = LoggerFactory.getLogger(UserProfileManager.class);
    private static final Duration REPOSITORY_TIMEOUT = Duration.ofSeconds(3);

    private final UserCommunicationProfileRepository profileRepository;
    private final UserRepository userRepository;

    public UserProfileManager(UserCommunicationProfileRepository profileRepository, UserRepository userRepository) {
        this.profileRepository = profileRepository;
        this.userRepository = userRepository;
    }

    public Mono<Void> analyzeAndSaveUserProfile(Long userId, String messageText) {
        if (userId == null) {
            return Mono.empty();
        }

        return findUserByTelegramId(userId)
                .switchIfEmpty(createUser(userId))
                .flatMap(user -> findProfileByUserId(user.getId())
                        .defaultIfEmpty(new UserCommunicationProfile(user.getId()))
                        .flatMap(profile -> {
                            analyzeMessagePatterns(profile, messageText);
                            profile.updateFromMessage(messageText);
                            return persistProfile(profile);
                        }))
                .then();
    }

    public Mono<UserCommunicationProfile> getProfileByTelegramUserId(Long userId) {
        if (userId == null) {
            return Mono.empty();
        }
        return profileRepository.findByTelegramUserId(userId)
                .timeout(REPOSITORY_TIMEOUT)
                .doOnError(error -> log.warn("Failed to fetch profile for user {}: {}", userId, error.getMessage()));
    }

    public Mono<User> getUserByInternalId(Long internalUserId) {
        if (internalUserId == null) {
            return Mono.empty();
        }
        return userRepository.findById(internalUserId)
                .timeout(REPOSITORY_TIMEOUT)
                .doOnError(error -> log.warn("Failed to fetch user {}: {}", internalUserId, error.getMessage()));
    }

    public Optional<User> getUserByInternalIdBlocking(Long internalUserId) {
        return internalUserId == null
                ? Optional.empty()
                : userRepository.findById(internalUserId).blockOptional(REPOSITORY_TIMEOUT);
    }

    public Mono<Boolean> isProfileReliable(Long userId) {
        return getProfileByTelegramUserId(userId)
                .map(UserCommunicationProfile::isReliable)
                .defaultIfEmpty(false);
    }

    private void analyzeMessagePatterns(UserCommunicationProfile profile, String messageText) {
        int formality = analyzeFormality(messageText);
        if (profile.getFormalityLevel() == null) {
            profile.setFormalityLevel(formality);
        } else {
            profile.setFormalityLevel((profile.getFormalityLevel() * 3 + formality) / 4);
        }

        long emoticonCount = messageText.chars().filter(ch -> "😀😁😂🤣😃😄😅😆😉😊😋😎😍😘😗😙😚☺😌😛😜😝🤑🤗🤔😐😑😶😏😒🙄😬🤐😷🤒🤕😴💤😪😵😲😳😨😰😥😓🤤😭😱😖😣😞😟😤😢😮😦😧😈👿😠😡💯".indexOf(ch) >= 0).count();
        double emoticonFreq = (messageText.length() > 0) ? (double) emoticonCount / messageText.length() : 0.0;
        profile.setEmoticonUsageFrequency(emoticonFreq);

        if (messageText.contains("...")) {
            profile.setPunctuationStyle("excessive");
        } else if (messageText.chars().filter(ch -> ch == '.' || ch == '!' || ch == '?').count() < 2) {
            profile.setPunctuationStyle("minimal");
        } else {
            profile.setPunctuationStyle("standard");
        }

        boolean hasSlang = messageText.toLowerCase().contains("прикольно") ||
                messageText.toLowerCase().contains("топ") ||
                messageText.toLowerCase().contains("кльово");
        profile.setUsesSlang(hasSlang);
    }

    private int analyzeFormality(String text) {
        String lower = text.toLowerCase();
        int formalityScore = 3; // Neutral
        if (lower.contains("ви ") || lower.contains("ваш")) formalityScore += 1;
        if (lower.contains("будь ласка") || lower.contains("дякую")) formalityScore += 1;
        if (lower.contains("ти ") || lower.contains("твій")) formalityScore -= 1;
        if (lower.contains("плиз") || lower.contains("спс")) formalityScore -= 1;
        if (lower.contains("прикольно") || lower.contains("кльово")) formalityScore -= 1;
        return Math.max(1, Math.min(5, formalityScore));
    }

    private Mono<User> findUserByTelegramId(Long telegramUserId) {
        if (telegramUserId == null) {
            return Mono.empty();
        }
        return userRepository.findByTelegramUserId(telegramUserId)
                .timeout(REPOSITORY_TIMEOUT)
                .doOnError(error -> log.warn("Failed to load user by telegram id {}: {}", telegramUserId, error.getMessage()));
    }

    private Mono<UserCommunicationProfile> findProfileByUserId(Long userId) {
        if (userId == null) {
            return Mono.empty();
        }
        return profileRepository.findByUserId(userId)
                .timeout(REPOSITORY_TIMEOUT)
                .doOnError(error -> log.warn("Failed to load profile for user {}: {}", userId, error.getMessage()));
    }

    private Mono<User> createUser(Long telegramUserId) {
        User user = new User();
        user.setTelegramUserId(telegramUserId);
        user.setAiEnabled(true);
        user.setCommunicationStyle(CommunicationStyle.CASUAL);
        user.setResponseLength(ResponseLength.MEDIUM);
        user.setLanguagePreference("uk");
        return persistUser(user);
    }

    private Mono<UserCommunicationProfile> persistProfile(UserCommunicationProfile profile) {
        if (profile == null) {
            return Mono.empty();
        }
        return profileRepository.save(profile)
                .timeout(REPOSITORY_TIMEOUT)
                .doOnError(error -> log.warn("Failed to persist profile {}: {}", profile.getUserId(), error.getMessage()));
    }

    private Mono<User> persistUser(User user) {
        return userRepository.save(user)
                .timeout(REPOSITORY_TIMEOUT)
                .doOnError(error -> log.warn("Failed to persist user {}: {}", user.getTelegramUserId(), error.getMessage()));
    }
}
