package com.example.ratelimit.config;

import io.lettuce.core.resource.ClientResources;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.util.concurrent.atomic.AtomicLong;

@Configuration
public class MetricsConfig {

    /**
     * Custom metrics binder to expose additional performance metrics
     */
    @Bean
    public MeterBinder customMetricsBinder(MeterRegistry registry) {
        return (reg) -> {
            // Thread pool metrics
            Gauge.builder("app.thread.active", Thread::activeCount)
                    .description("Number of active threads in the JVM")
                    .register(reg);

            Gauge.builder("app.thread.daemon", () -> {
                        ThreadGroup rootGroup = Thread.currentThread().getThreadGroup();
                        while (rootGroup.getParent() != null) {
                            rootGroup = rootGroup.getParent();
                        }
                        Thread[] threads = new Thread[rootGroup.activeCount()];
                        rootGroup.enumerate(threads);
                        long daemonCount = 0;
                        for (Thread t : threads) {
                            if (t != null && t.isDaemon()) daemonCount++;
                        }
                        return daemonCount;
                    })
                    .description("Number of daemon threads")
                    .register(reg);

            // CPU metrics
            Gauge.builder("app.cpu.available_processors", Runtime.getRuntime()::availableProcessors)
                    .description("Number of available CPU processors")
                    .register(reg);
        };
    }

    /**
     * Expose Lettuce connection pool metrics
     */
    @Bean
    public MeterBinder lettuceMetricsBinder(@Autowired(required = false) LettuceConnectionFactory connectionFactory,
                                            MeterRegistry registry) {
        return (reg) -> {
            if (connectionFactory != null) {
                // Register pool metrics if available
                Gauge.builder("redis.connection.active", () -> {
                            try {
                                // Attempt to get pool metrics
                                return connectionFactory.getConnection() != null ? 1 : 0;
                            } catch (Exception e) {
                                return -1;
                            }
                        })
                        .description("Redis connection pool active status")
                        .register(reg);
            }
        };
    }
}
