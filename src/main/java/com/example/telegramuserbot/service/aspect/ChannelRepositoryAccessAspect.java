package com.example.telegramuserbot.service.aspect;

import com.example.telegramuserbot.service.TelegramConnectionCoordinator;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Aspect
@Component
public class ChannelRepositoryAccessAspect {

    private final TelegramConnectionCoordinator coordinator;

    public ChannelRepositoryAccessAspect(TelegramConnectionCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @Around("execution(* com.example.telegramuserbot.repository.ChannelRepository.*(..))")
    public Object guardChannelRepositoryAccess(ProceedingJoinPoint pjp) throws Throwable {
        Object result = pjp.proceed();

        if (result instanceof Mono<?> mono) {
            @SuppressWarnings("unchecked")
            Mono<Object> typedMono = (Mono<Object>) mono;
            return coordinator.guardMonoAsReader(typedMono);
        }
        if (result instanceof Flux<?> flux) {
            @SuppressWarnings("unchecked")
            Flux<Object> typedFlux = (Flux<Object>) flux;
            // ИЗМЕНЕНО: вызываем новый метод
            return coordinator.guardFluxAsReader(typedFlux);
        }
        return result;
    }
}