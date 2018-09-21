package com.github.mwiede.metrics.feign;

import org.eclipse.microprofile.metrics.annotation.Counted;
import org.eclipse.microprofile.metrics.annotation.Metered;
import org.eclipse.microprofile.metrics.annotation.Timed;

import com.github.mwiede.metrics.annotation.ExceptionMetered;
import com.github.mwiede.metrics.annotation.ResponseMetered;

import feign.RequestLine;

@Timed
@Metered
@Counted
@ResponseMetered
@ExceptionMetered
interface MyClientWithAnnotationOnClassLevel {

    @RequestLine("POST /")
    void myMethod();
}
