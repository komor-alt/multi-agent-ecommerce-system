package com.ecommerce.aftersales.repository;

import com.ecommerce.aftersales.entity.AfterSalesRunEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真库（H2）验证 claimReady 的原子性：并发调用最多一个调用方认领成功，
 * startedAt 在认领时写入，其余调用方返回 0。
 * 测试方法自身不开启事务（NOT_SUPPORTED），每次仓库调用各自提交，
 * 模拟真实 HTTP 线程各自独立事务的并发形态。
 */
@DataJpaTest
class AfterSalesRunRepositoryConcurrencyTest {

    @Autowired
    private AfterSalesRunRepository runRepository;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentStartCallsClaimReadyExactlyOnce() throws Exception {
        runRepository.save(AfterSalesRunEntity.builder()
                .id("run-c1")
                .ticketId("ticket-c1")
                .status("READY")
                .maxSteps(6)
                .stepCount(0)
                .build());

        int threadCount = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return runRepository.claimReady("run-c1", Instant.now());
            }));
        }
        ready.await();
        go.countDown();
        int totalClaims = 0;
        for (Future<Integer> future : futures) {
            int result = future.get(15, TimeUnit.SECONDS);
            assertThat(result).isIn(0, 1);
            totalClaims += result;
        }
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);

        // 8 个并发调用只有 1 个认领成功：最多启动一次。
        assertThat(totalClaims).isEqualTo(1);

        AfterSalesRunEntity run = runRepository.findById("run-c1").orElseThrow();
        assertThat(run.getStatus()).isEqualTo("RUNNING");
        // startedAt 在真正认领启动时写入，而不是 prepare 时。
        assertThat(run.getStartedAt()).isNotNull();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void claimReadyRefusesNonReadyRuns() {
        runRepository.save(AfterSalesRunEntity.builder()
                .id("run-c2")
                .ticketId("ticket-c2")
                .status("COMPLETED")
                .maxSteps(6)
                .stepCount(5)
                .startedAt(Instant.now())
                .completedAt(Instant.now())
                .durationMs(42L)
                .build());

        assertThat(runRepository.claimReady("run-c2", Instant.now())).isZero();
        assertThat(runRepository.claimReady("run-missing", Instant.now())).isZero();
        AfterSalesRunEntity run = runRepository.findById("run-c2").orElseThrow();
        assertThat(run.getStatus()).isEqualTo("COMPLETED");
        assertThat(run.getStartedAt()).isNotNull();
    }
}
