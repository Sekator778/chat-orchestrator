package com.example.telegramuserbot.service;

import reactor.core.publisher.Mono;

public interface MediaStorageService {
    /**
     * Завантажує файл із Telegram (через TDLib fileId),
     * переміщує у структуру {storageDir}/{chatId}/ і повертає відносний шлях.
     * @return A Mono emitting the relative path of the stored file, or empty if storage fails.
     */
    Mono<String> storeMedia(Long chatId, long fileId, String originalFileName);
}
