/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.test


import groovy.transform.stc.ClosureParams
import groovy.transform.stc.SimpleType
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.instrumentation.test.asserts.InMemoryExporterAssert
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.ReadWriteSpan
import io.opentelemetry.sdk.trace.ReadableSpan
import io.opentelemetry.sdk.trace.SpanProcessor
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import org.junit.Before
import spock.lang.Specification

/**
 * A spock test runner which automatically initializes an in-memory exporter that can be used to
 * verify traces.
 */
abstract class InstrumentationTestRunner extends Specification {

  protected static final InMemorySpanExporter testExporter

  private static boolean forceFlushCalled

  static {
    testExporter = InMemorySpanExporter.create()
    // TODO this is probably temporary until default propagators are supplied by SDK
    //  https://github.com/open-telemetry/opentelemetry-java/issues/1742
    //  currently checking against no-op implementation so that it won't override aws-lambda
    //  propagator configuration
    if (OpenTelemetry.getGlobalPropagators().getTextMapPropagator().getClass().getSimpleName() == "NoopTextMapPropagator") {
      OpenTelemetry.setGlobalPropagators(
        ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
    }
    OpenTelemetrySdk.getGlobalTracerManagement().addSpanProcessor(new FlushTrackingSpanProcessor())
    OpenTelemetrySdk.getGlobalTracerManagement()
      .addSpanProcessor(SimpleSpanProcessor.builder(testExporter).build())
  }

  @Before
  void beforeTest() {
    testExporter.reset()
    forceFlushCalled = false
  }

  protected static boolean forceFlushCalled() {
    return forceFlushCalled
  }

  protected static void assertTraces(
    final int size,
    @ClosureParams(
      value = SimpleType,
      options = "io.opentelemetry.instrumentation.test.asserts.ListWriterAssert")
    @DelegatesTo(value = InMemoryExporterAssert, strategy = Closure.DELEGATE_FIRST)
    final Closure spec) {
    InMemoryExporterAssert.assertTraces({ testExporter.getFinishedSpanItems() }, size, spec)
  }

  static class FlushTrackingSpanProcessor implements SpanProcessor {
    @Override
    void onStart(Context parentContext, ReadWriteSpan span) {
    }

    @Override
    boolean isStartRequired() {
      return false
    }

    @Override
    void onEnd(ReadableSpan span) {
    }

    @Override
    boolean isEndRequired() {
      return false
    }

    @Override
    CompletableResultCode forceFlush() {
      forceFlushCalled = true
      return CompletableResultCode.ofSuccess()
    }
  }
}
