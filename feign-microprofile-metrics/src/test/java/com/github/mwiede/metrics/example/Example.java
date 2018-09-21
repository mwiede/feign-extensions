package com.github.mwiede.metrics.example;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.eclipse.microprofile.metrics.MetricRegistry;
import org.eclipse.microprofile.metrics.annotation.Counted;
import org.eclipse.microprofile.metrics.annotation.Metered;
import org.eclipse.microprofile.metrics.annotation.Timed;

import com.github.mwiede.metrics.feign.FeignMetricsInvocationHandlerFactoryDecorator;
import com.github.mwiede.metrics.feign.FeignWithMetrics;

import feign.Param;
import feign.RequestLine;
import feign.gson.GsonDecoder;
import io.helidon.config.Config;
import io.helidon.metrics.RegistryFactory;

/**
 * An example showing how {@link FeignMetricsInvocationHandlerFactoryDecorator} can be used.
 */
public class Example {

    @Timed
    @Metered
    @Counted
    interface GitHub {
        @RequestLine("GET /repos/{owner}/{repo}/contributors")
        List<Contributor> contributors(@Param("owner") String owner, @Param("repo") String repo);
    }

    static class Contributor {
        String login;
        int contributions;
    }

    public static void main(final String... args) {

        final MetricRegistry metricRegistry =
                RegistryFactory.createSeFactory(Config.empty()).getRegistry(MetricRegistry.Type.APPLICATION);

        final GitHub github = FeignWithMetrics.builder(metricRegistry).decoder(new GsonDecoder())
                .target(GitHub.class, "https://api.github.com");
        try {
            // Fetch and print a list of the contributors to this library.
            final List<Contributor> contributors = github.contributors("mwiede", "metrics-feign");
            for (final Contributor contributor : contributors) {
                System.out.println(contributor.login + " (" + contributor.contributions + ")");
            }
        } finally {
            // print?
            metricRegistry.getCounters();
        }
    }
}
