package com.github.mwiede.metrics.feign;

import static org.eclipse.microprofile.metrics.MetricRegistry.name;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.eclipse.microprofile.metrics.Counter;
import org.eclipse.microprofile.metrics.Meter;
import org.eclipse.microprofile.metrics.MetricRegistry;
import org.eclipse.microprofile.metrics.Timer;
import org.eclipse.microprofile.metrics.annotation.Counted;
import org.eclipse.microprofile.metrics.annotation.Metered;
import org.eclipse.microprofile.metrics.annotation.Timed;

import com.github.mwiede.metrics.annotation.ExceptionMetered;
import com.github.mwiede.metrics.annotation.ResponseMetered;

import feign.InvocationHandlerFactory;
import feign.Target;

/**
 * A decorator class, which takes all methods given in
 * {@link InvocationHandlerFactory#create(Target, Map)} and initializes a metric for each
 * annotation of micropfiles metrics {@link Timed}, {@link Metered}, {@link Counted}
 * and custom annotations {@link com.github.mwiede.metrics.annotation.ExceptionMetered} and
 * {@link com.github.mwiede.metrics.annotation.ResponseMetered} into the global {@link MetricRegistry}.
 * Additionally, it triggers the metric during invocation of the
 * {@link feign.InvocationHandlerFactory.MethodHandler}s.
 * <p>
 * This class is inspired by
 * com.codahale.metrics.jersey2.InstrumentedResourceMethodApplicationListener.
 */
public class FeignMetricsInvocationHandlerFactoryDecorator implements InvocationHandlerFactory {

    static final ThreadLocal<Method> ACTUAL_METHOD = new ThreadLocal<>();
    static final ThreadLocal<ResponseMeterMetric> ACTUAL_METRIC = new ThreadLocal<>();

    private final MetricRegistry metricRegistry;
    private final InvocationHandlerFactory delegate;

    private final ConcurrentMap<Method, Timer> timers = new ConcurrentHashMap<>();
    private final ConcurrentMap<Method, Meter> meters = new ConcurrentHashMap<>();
    private final ConcurrentMap<Method, Counter> counters = new ConcurrentHashMap<>();
    private final ConcurrentMap<Method, ExceptionMeterMetric> exceptionMeters = new ConcurrentHashMap<>();
    private final ConcurrentMap<Method, ResponseMeterMetric> responseMeters = new ConcurrentHashMap<>();

    public FeignMetricsInvocationHandlerFactoryDecorator(final InvocationHandlerFactory original,
            final MetricRegistry metricRegistry) {
        this.delegate = original;
        this.metricRegistry = metricRegistry;
    }

    /**
     * A private class to maintain the metric for a method annotated with the {@link ExceptionMetered}
     * annotation, which needs to maintain both a meter and a cause for which the meter should be
     * updated.
     */
    private static class ExceptionMeterMetric {
        public final Meter meter;
        public final Class<? extends Throwable> cause;

        public ExceptionMeterMetric(final MetricRegistry registry, final Method method,
                final ExceptionMetered exceptionMetered) {
            final String name = chooseName(exceptionMetered.name(), exceptionMetered.absolute(), method,
                    ExceptionMetered.DEFAULT_NAME_SUFFIX);
            this.meter = registry.meter(name);
            this.cause = exceptionMetered.cause();
        }
    }

    /**
     * A private class to maintain the metrics for a method annotated with the {@link ResponseMetered}
     * annotation, which needs to maintain meters for different response codes
     */
    static class ResponseMeterMetric {
        public final List<Meter> meters;

        public ResponseMeterMetric(final MetricRegistry registry, final Method method,
                final ResponseMetered responseMetered) {
            final String metricName = chooseName(responseMetered.name(), responseMetered.absolute(), method);
            this.meters =
                    Collections.unmodifiableList(Arrays.asList(registry.meter(name(metricName, "1xx-responses")), // 1xx
                            registry.meter(name(metricName, "2xx-responses")), // 2xx
                            registry.meter(name(metricName, "3xx-responses")), // 3xx
                            registry.meter(name(metricName, "4xx-responses")), // 4xx
                            registry.meter(name(metricName, "5xx-responses")) // 5xx
                    ));
        }
    }

    /**
     * A decorator, which triggers certain metrics, if found.
     */
    private static class MethodHandlerDecorator implements MethodHandler {

        private final Method method;
        private final MethodHandler methodHandler;
        private final ConcurrentMap<Method, Meter> meters;
        private final ConcurrentMap<Method, Timer> timers;
        private final ConcurrentMap<Method, Counter> counters;
        private final ConcurrentMap<Method, ResponseMeterMetric> responseMeters;
        private final ConcurrentMap<Method, ExceptionMeterMetric> exceptionMeters;
        private Timer.Context context = null;

        public MethodHandlerDecorator(final Method method, final MethodHandler methodHandler,
                final ConcurrentMap<Method, Meter> meters, final ConcurrentMap<Method, Timer> timers,
                final ConcurrentMap<Method, Counter> counters,
                final ConcurrentMap<Method, ExceptionMeterMetric> exceptionMeters,
                final ConcurrentMap<Method, ResponseMeterMetric> responseMeters) {
            this.method = method;
            this.methodHandler = methodHandler;
            this.meters = meters;
            this.counters = counters;
            this.exceptionMeters = exceptionMeters;
            this.responseMeters = responseMeters;
            this.timers = timers;
        }

        @Override
        public Object invoke(final Object[] argv) throws Throwable {
            try {

                final Meter meter = this.meters.get(method);
                if (meter != null) {
                    meter.mark();
                }

                final Timer timer = this.timers.get(method);
                if (timer != null) {
                    this.context = timer.time();
                }

                final Counter counter = this.counters.get(method);
                if (counter != null) {
                    counter.inc();
                }

                ACTUAL_METHOD.set(method);
                ACTUAL_METRIC.set(this.responseMeters.get(method));

                return methodHandler.invoke(argv);

            } catch (final Exception e) {

                final FeignMetricsInvocationHandlerFactoryDecorator.ExceptionMeterMetric metric =
                        (method != null) ? this.exceptionMeters.get(method) : null;

                if (metric != null && (metric.cause.isAssignableFrom(e.getClass()) || (e.getCause() != null
                        && metric.cause.isAssignableFrom(e.getCause().getClass())))) {
                    metric.meter.mark();
                }

                throw e;
            } finally {
                if (this.context != null) {
                    this.context.close();
                }
                ACTUAL_METHOD.set(null);
            }
        }
    }

    @Override
    public InvocationHandler create(final Target target, final Map<Method, MethodHandler> dispatch) {

        for (final Map.Entry<Method, MethodHandler> entry : dispatch.entrySet()) {

            registerMetricsForMethod(entry.getKey());

            entry.setValue(new MethodHandlerDecorator(entry.getKey(), entry.getValue(), meters, timers, counters,
                    exceptionMeters, responseMeters));
        }

        return delegate.create(target, dispatch);
    }

    private void registerMetricsForMethod(final Method method) {

        final Timed classLevelTimed = getClassLevelAnnotation(method.getDeclaringClass(), Timed.class);
        final Metered classLevelMetered = getClassLevelAnnotation(method.getDeclaringClass(), Metered.class);
        final Counted classLevelCounted = getClassLevelAnnotation(method.getDeclaringClass(), Counted.class);
        final ResponseMetered classLevelResponseMetered =
                getClassLevelAnnotation(method.getDeclaringClass(), ResponseMetered.class);
        final ExceptionMetered classLevelExceptionMetered =
                getClassLevelAnnotation(method.getDeclaringClass(), ExceptionMetered.class);

        registerTimedAnnotations(method, classLevelTimed);
        registerMeteredAnnotations(method, classLevelMetered);
        registerCountedAnnotations(method, classLevelCounted);
        registerResponseMeteredAnnotations(method, classLevelResponseMetered);
        registerExceptionMeteredAnnotations(method, classLevelExceptionMetered);

    }

    private <T extends Annotation> T getClassLevelAnnotation(final Class clazz, final Class<T> annotationClazz) {
        return (T) clazz.getAnnotation(annotationClazz);
    }

    private void registerTimedAnnotations(final Method method, final Timed classLevelTimed) {
        if (classLevelTimed != null) {
            timers.putIfAbsent(method, timerMetric(this.metricRegistry, method, classLevelTimed));
            return;
        }

        final Timed annotation = method.getAnnotation(Timed.class);

        if (annotation != null) {
            timers.putIfAbsent(method, timerMetric(this.metricRegistry, method, annotation));
        }
    }

    private void registerMeteredAnnotations(final Method method, final Metered classLevelMetered) {
        if (classLevelMetered != null) {
            meters.putIfAbsent(method, meterMetric(metricRegistry, method, classLevelMetered));
            return;
        }
        final Metered annotation = method.getAnnotation(Metered.class);

        if (annotation != null) {
            meters.putIfAbsent(method, meterMetric(metricRegistry, method, annotation));
        }
    }

    private void registerResponseMeteredAnnotations(final Method method,
            final ResponseMetered classLevelResponseMetered) {

        if (classLevelResponseMetered != null) {
            responseMeters.putIfAbsent(method,
                    new FeignMetricsInvocationHandlerFactoryDecorator.ResponseMeterMetric(metricRegistry, method,
                            classLevelResponseMetered));
            return;
        }
        final ResponseMetered annotation = method.getAnnotation(ResponseMetered.class);

        if (annotation != null) {
            responseMeters.putIfAbsent(method,
                    new FeignMetricsInvocationHandlerFactoryDecorator.ResponseMeterMetric(metricRegistry, method,
                            annotation));
        }

    }

    private void registerExceptionMeteredAnnotations(final Method method,
            final ExceptionMetered classLevelExceptionMetered) {

        if (classLevelExceptionMetered != null) {
            exceptionMeters.putIfAbsent(method,
                    new FeignMetricsInvocationHandlerFactoryDecorator.ExceptionMeterMetric(metricRegistry, method,
                            classLevelExceptionMetered));
            return;
        }
        final ExceptionMetered annotation = method.getAnnotation(ExceptionMetered.class);

        if (annotation != null) {
            exceptionMeters.putIfAbsent(method,
                    new FeignMetricsInvocationHandlerFactoryDecorator.ExceptionMeterMetric(metricRegistry, method,
                            annotation));
        }
    }

    private void registerCountedAnnotations(final Method method, final Counted classLevelCounted) {
        if (classLevelCounted != null) {
            counters.putIfAbsent(method, counterMetric(metricRegistry, method, classLevelCounted));
            return;
        }
        final Counted annotation = method.getAnnotation(Counted.class);

        if (annotation != null) {
            counters.putIfAbsent(method, counterMetric(metricRegistry, method, annotation));
        }
    }

    private static Timer timerMetric(final MetricRegistry registry, final Method method, final Timed timed) {
        final String name = chooseName(timed.name(), timed.absolute(), method, "Timed");
        return registry.timer(name);
    }

    private static Counter counterMetric(final MetricRegistry registry, final Method method, final Counted metered) {
        final String name = chooseName(metered.name(), metered.absolute(), method, "Counted");
        return registry.counter(name);
    }

    private static Meter meterMetric(final MetricRegistry registry, final Method method, final Metered metered) {
        final String name = chooseName(metered.name(), metered.absolute(), method, "Metered");
        return registry.meter(name);
    }

    static String chooseName(final String explicitName, final boolean absolute, final Method method,
            final String... suffixes) {
        if (explicitName != null && !explicitName.isEmpty()) {
            if (absolute) {
                return explicitName;
            }
            return name(method.getDeclaringClass(), explicitName);
        }

        return name(name(method.getDeclaringClass(), method.getName()), suffixes);
    }
}
