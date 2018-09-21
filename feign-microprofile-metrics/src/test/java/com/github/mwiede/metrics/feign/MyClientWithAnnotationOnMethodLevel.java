package com.github.mwiede.metrics.feign;

import org.eclipse.microprofile.metrics.annotation.Counted;
import org.eclipse.microprofile.metrics.annotation.Metered;
import org.eclipse.microprofile.metrics.annotation.Timed;

import com.github.mwiede.metrics.annotation.ExceptionMetered;
import com.github.mwiede.metrics.annotation.ResponseMetered;

import feign.RequestLine;

/**
 * Created by mwiedemann on 24.10.2017.
 */
interface MyClientWithAnnotationOnMethodLevel {
    @Timed
    @Metered
    @Counted
    @ResponseMetered
    @ExceptionMetered
    @RequestLine("POST /")
    void myMethod();
}
