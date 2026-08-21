package com.example.telegramuserbot.repository;

import com.example.telegramuserbot.domain.PipelineSnapshot;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Reactive repository for pipeline health snapshots.
 */
public interface PipelineSnapshotRepository extends ReactiveCrudRepository<PipelineSnapshot, Long> {

    /**
     * Returns the N most recent snapshots, newest first.
     *
     * @param limit maximum number of records to return
     * @return flux of recent snapshots
     */
    @Query("SELECT * FROM bot.pipeline_snapshots ORDER BY snapshotted_at DESC LIMIT :limit")
    Flux<PipelineSnapshot> findRecent(int limit);

    /**
     * Counts anomaly snapshots in the last N hours.
     *
     * @param hours lookback window
     * @return count of anomalies
     */
    @Query("SELECT COUNT(*) FROM bot.pipeline_snapshots "
            + "WHERE anomaly = TRUE AND snapshotted_at > NOW() - (INTERVAL '1 hour' * :hours)")
    Mono<Long> countAnomaliesInLastHours(int hours);

    /**
     * Deletes snapshots older than the given number of days to prevent table growth.
     *
     * @param days retention window
     * @return count of deleted rows
     */
    @Query("DELETE FROM bot.pipeline_snapshots "
            + "WHERE snapshotted_at < NOW() - (INTERVAL '1 day' * :days)")
    Mono<Long> deleteOlderThanDays(int days);
}
