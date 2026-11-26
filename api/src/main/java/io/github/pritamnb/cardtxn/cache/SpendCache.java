package io.github.pritamnb.cardtxn.cache;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Today's spend per card, kept in Redis for fast reads (e.g. spending-limit checks).
 * Stored in minor units (pence) with INCRBY: never use floating point for money.
 * Key: spend:{cardId}:{yyyy-MM-dd}, expires after 2 days.
 */
@Component
public class SpendCache {

    private static final Duration TTL = Duration.ofDays(2);

    private final ReactiveStringRedisTemplate redis;

    public SpendCache(ReactiveStringRedisTemplate redis) {
        this.redis = redis;
    }

    public Mono<BigDecimal> add(String cardId, LocalDate day, BigDecimal amount) {
        String key = key(cardId, day);
        long pence = amount.movePointRight(2).longValueExact();
        return redis.opsForValue().increment(key, pence)
                .flatMap(total -> redis.expire(key, TTL).thenReturn(toPounds(total)));
    }

    public Mono<BigDecimal> get(String cardId, LocalDate day) {
        return redis.opsForValue().get(key(cardId, day))
                .map(v -> toPounds(Long.parseLong(v)))
                .defaultIfEmpty(BigDecimal.ZERO.setScale(2));
    }

    private static String key(String cardId, LocalDate day) {
        return "spend:" + cardId + ":" + day;
    }

    private static BigDecimal toPounds(long pence) {
        return BigDecimal.valueOf(pence, 2);
    }
}
