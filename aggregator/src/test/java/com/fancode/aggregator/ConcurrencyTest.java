package com.fancode.aggregator;

import com.fancode.aggregator.ingest.BallData;
import com.fancode.aggregator.ingest.IngestionService;
import com.fancode.aggregator.ingest.IngestionService.PushOutcome;
import com.fancode.aggregator.ingest.IngestionService.PushResult;
import com.fancode.aggregator.ingest.SnapshotData;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** Many writers for the same match at once must still produce the rule-defined result. */
class ConcurrencyTest extends IntegrationTestBase {

    private static final int THREADS = 16;

    @Autowired
    IngestionService ingestion;

    @Test
    void concurrentSnapshotsAndRevisionsConvergeOnTheNewestTuple() throws Exception {
        List<Callable<PushResult>> tasks = new ArrayList<>();
        for (int seq = 1; seq <= 20; seq++) {
            for (int rev = 1; rev <= 3; rev++) {
                int s = seq;
                int r = rev;
                tasks.add(() -> ingestion.ingestPush("provider-a", "d-" + s + "-" + r, "h", "a-match-1001",
                        new SnapshotData(s, r, "live", 1, s * 10 + r, 0, s + "." + r, null),
                        new BallData("ball-" + (s % 5), s % 5, r, 1, 0, s % 5, "rev " + r, null)));
            }
        }
        Collections.shuffle(tasks, new Random(42)); // fixed seed: deterministic shuffle

        runConcurrently(tasks);

        Map<String, Object> snap = jdbc.queryForMap("SELECT sequence, revision, runs FROM provider_snapshots");
        assertThat(snap).containsEntry("sequence", 20L).containsEntry("revision", 3).containsEntry("runs", 203);
        assertThat(count("commentary")).isEqualTo(5);
        assertThat(jdbc.queryForList("SELECT DISTINCT revision FROM commentary", Integer.class)).containsExactly(3);
        assertThat(count("processed_deliveries")).isEqualTo(60);
    }

    @Test
    void sameDeliveryIdProcessedConcurrentlyIsAppliedOnce() throws Exception {
        List<Callable<PushResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            tasks.add(() -> ingestion.ingestPush("provider-a", "same-delivery", "h", "a-match-1001",
                    new SnapshotData(7, 1, "live", 1, 50, 1, "8.2", null),
                    new BallData("b7", 7, 1, 1, 8, 2, "four", null)));
        }

        List<PushResult> results = runConcurrently(tasks);

        assertThat(results).filteredOn(r -> r.outcome() == PushOutcome.ACCEPTED).hasSize(1);
        assertThat(results).filteredOn(r -> r.outcome() == PushOutcome.DUPLICATE).hasSize(29);
        assertThat(count("processed_deliveries")).isEqualTo(1);
        assertThat(count("commentary")).isEqualTo(1);
    }

    private static <T> List<T> runConcurrently(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
