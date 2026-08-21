package com.example.telegramuserbot.service.llm.dto;

import java.util.List;

/**
 * @author Sekator
 * @created 27 кві, 2025
 */

public record DeepSeekChatResponse(
        String id,
        List<ResponseChoice> choices,
        long created,
        String model,
        String object,
        UsageInfo usage
) {}
