package com.example.telegramuserbot.service.llm.dto;

/**
 * @author Sekator
 * @created 27 кві, 2025
 */
// Відповідь (спрощена, беремо тільки основне)
public record ResponseMessage(String role, String content) {}