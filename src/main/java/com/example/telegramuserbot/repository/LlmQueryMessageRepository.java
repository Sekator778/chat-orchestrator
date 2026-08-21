package com.example.telegramuserbot.repository;

import com.example.telegramuserbot.domain.LlmQueryMessage;
import org.springframework.data.r2dbc.repository.R2dbcRepository;

public interface LlmQueryMessageRepository extends R2dbcRepository<LlmQueryMessage, Long> {
}
