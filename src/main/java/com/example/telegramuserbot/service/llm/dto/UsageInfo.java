package com.example.telegramuserbot.service.llm.dto;

/**
 * @author Sekator
 * @created 27 кві, 2025
 */
public record UsageInfo(
        Integer prompt_tokens,
        Integer completion_tokens,
        Integer total_tokens
) {}
