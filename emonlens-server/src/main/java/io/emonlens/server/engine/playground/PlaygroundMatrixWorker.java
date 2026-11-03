package io.emonlens.server.engine.playground;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import org.springframework.stereotype.Component;

import io.emonlens.server.config.EmonLensProperties;
import io.emonlens.server.storage.entity.PlaygroundMatrixEntity;
import io.emonlens.server.storage.mapper.PlaygroundMatrixMapper;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Playground 矩阵执行线程（R1-2）：认领 PENDING 矩阵 → 网格并发执行 → 收口。
 *
 * <p>与 dataset run worker 分离：矩阵是试跑，允许被中断（进程重启时残留 RUNNING 直接标失败），
 * 不需要租约、续跑与预算账本——成本护栏在受理阶段已经硬确认过。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlaygroundMatrixWorker {

    private final PlaygroundMatrixService matrixService;
    private final PlaygroundMatrixMapper matrixMapper;
    private final EmonLensProperties properties;

    private volatile boolean running = true;
    private final List<Thread> workers = new ArrayList<>();

    @PostConstruct
    public void start() {
        if (!properties.getWorker().isEnabled()) {
            log.info("playground matrix worker disabled by emonlens.worker.enabled=false");
            return;
        }
        recoverInterrupted();
        int threadCount = Math.max(1, Math.min(4, properties.getPlayground().getMatrix().getWorkerThreads()));
        for (int i = 0; i < threadCount; i++) {
            Thread thread = new Thread(this::loop, "playground-matrix-worker-" + i);
            thread.setDaemon(true);
            workers.add(thread);
            thread.start();
        }
        log.info("playground matrix workers started, threads={}", threadCount);
    }

    @PreDestroy
    public void stop() {
        running = false;
        for (Thread thread : workers) {
            thread.interrupt();
        }
    }

    /** 进程重启后残留的 RUNNING 矩阵不可能再有执行者：明确标失败，不留下“永远在跑”的假象。 */
    private void recoverInterrupted() {
        try {
            int updated = matrixMapper.update(null, Wrappers.<PlaygroundMatrixEntity>lambdaUpdate()
                    .eq(PlaygroundMatrixEntity::getStatus, PlaygroundMatrixEntity.STATUS_RUNNING)
                    .set(PlaygroundMatrixEntity::getStatus, PlaygroundMatrixEntity.STATUS_FAILED)
                    .set(PlaygroundMatrixEntity::getError, "实例重启，矩阵执行中断；请重新发起"));
            if (updated > 0) {
                log.info("playground matrices marked FAILED after restart: {}", updated);
            }
        } catch (Exception e) {
            log.error("playground matrix interruption recovery failed", e);
        }
    }

    private void loop() {
        while (running) {
            try {
                PlaygroundMatrixEntity matrix = matrixService.claimNextPending();
                if (matrix != null) {
                    try {
                        matrixService.execute(matrix);
                    } catch (Exception e) {
                        log.error("playground matrix execution failed, matrix={}", matrix.getId(), e);
                        matrixMapper.update(null, Wrappers.<PlaygroundMatrixEntity>lambdaUpdate()
                                .eq(PlaygroundMatrixEntity::getId, matrix.getId())
                                .set(PlaygroundMatrixEntity::getStatus, PlaygroundMatrixEntity.STATUS_FAILED)
                                .set(PlaygroundMatrixEntity::getError, truncate(String.valueOf(e.getMessage()))));
                    }
                    continue;
                }
                Thread.sleep(Math.max(200, properties.getPlayground().getMatrix().getPollIntervalMs()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.error("playground matrix worker loop error", e);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }
}
