package com.github.mwiede.metrics.feign;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import java.util.Date;
import java.util.Map;
import java.util.Set;

import org.eclipse.microprofile.metrics.Meter;
import org.eclipse.microprofile.metrics.MetricRegistry;
import org.junit.Before;
import org.junit.Test;

import feign.RetryableException;
import feign.Retryer;
import io.helidon.config.Config;
import io.helidon.metrics.RegistryFactory;

public class FeignMetricsRetryerDecoratorTest {

    MetricRegistry metricRegistry;

    @Before
    public void init() {
        metricRegistry = RegistryFactory.createSeFactory(Config.empty()).getRegistry(MetricRegistry.Type.APPLICATION);
    }

    @Test
    public void continueOrPropagate() {

        FeignMetricsInvocationHandlerFactoryDecorator.ACTUAL_METHOD.set(this.getClass().getDeclaredMethods()[0]);

        new FeignMetricsRetryerDecorator(new Retryer.Default(), metricRegistry).clone()
                .continueOrPropagate(new RetryableException("message", new Date()));

        assertEquals("wrong number of meter metrics.", 1, metricRegistry.getMeters().values().size());

        final Set<Map.Entry<String, Meter>> entries = metricRegistry.getMeters().entrySet();

        entries.forEach(entry -> {
            assertEquals(String.format("wrong number of invocations in metric %s", entry.getKey()), 1,
                    entry.getValue().getCount());
        });
    }

    @Test
    public void testClone() {
        final FeignMetricsRetryerDecorator feignMetricsRetryerDecorator =
                new FeignMetricsRetryerDecorator(new Retryer.Default(), metricRegistry);
        final Retryer clone = feignMetricsRetryerDecorator.clone();
        assertNotEquals(feignMetricsRetryerDecorator, clone);
    }

}
