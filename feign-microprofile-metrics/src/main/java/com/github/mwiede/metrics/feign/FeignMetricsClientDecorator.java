package com.github.mwiede.metrics.feign;

import java.io.IOException;

import feign.Client;
import feign.Request;
import feign.Request.Options;
import feign.Response;

/**
 * a decorator filling metrics of methods annotated with {@link com.github.mwiede.metrics.annotation.ResponseMetered}.
 */
public class FeignMetricsClientDecorator implements Client {

    private final Client delegate;

    public FeignMetricsClientDecorator(final Client client) {
        this.delegate = client;
    }

    @Override
    public Response execute(final Request request, final Options options) throws IOException {

        final FeignMetricsInvocationHandlerFactoryDecorator.ResponseMeterMetric metric =
                FeignMetricsInvocationHandlerFactoryDecorator.ACTUAL_METRIC.get();

        final Response response = delegate.execute(request, options);

        if (metric != null && response != null) {
            final int responseStatus = response.status() / 100;
            if (responseStatus >= 1 && responseStatus <= 5) {
                metric.meters.get(responseStatus - 1).mark();
            }
        }

        return response;
    }

}
