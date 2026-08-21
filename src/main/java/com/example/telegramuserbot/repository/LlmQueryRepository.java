package com.example.telegramuserbot.repository;

import com.example.telegramuserbot.domain.LlmQuery;
import org.springframework.data.r2dbc.repository.R2dbcRepository;

public interface LlmQueryRepository extends R2dbcRepository<LlmQuery, Long> {
}
