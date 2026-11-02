package it.giuseppefrattura.garminservice.config;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.FutureTask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersonalWellnessExecutorConfigTest {
    @Test void queuedWorkIsCancelledWhenTheApplicationStops() throws Exception {
        var context = new AnnotationConfigApplicationContext(PersonalWellnessExecutorConfig.class);
        ThreadPoolTaskExecutor executor = context.getBean("personalWellnessExecutor", ThreadPoolTaskExecutor.class);
        CountDownLatch started = new CountDownLatch(6);
        CountDownLatch release = new CountDownLatch(1);
        for (int i = 0; i < 6; i++) executor.execute(() -> {
            started.countDown();
            try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        assertTrue(started.await(2, TimeUnit.SECONDS));
        FutureTask<Integer> pending = new FutureTask<>(() -> 1);
        executor.execute(pending);
        context.close();
        assertTrue(pending.isCancelled());
        release.countDown();
    }
    @Test void springClosesBoundedExecutorWhenContextCloses() throws Exception {
        var context = new AnnotationConfigApplicationContext(PersonalWellnessExecutorConfig.class);
        ThreadPoolTaskExecutor executor = context.getBean("personalWellnessExecutor", ThreadPoolTaskExecutor.class);
        CountDownLatch ran = new CountDownLatch(1);
        executor.execute(ran::countDown);
        assertTrue(ran.await(2, TimeUnit.SECONDS));
        assertEquals(6, executor.getCorePoolSize());
        assertEquals(6, executor.getMaxPoolSize());
        context.close();
        assertTrue(executor.getThreadPoolExecutor().isShutdown());
    }
}
