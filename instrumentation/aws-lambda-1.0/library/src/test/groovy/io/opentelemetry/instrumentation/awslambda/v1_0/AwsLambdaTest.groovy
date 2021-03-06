/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.awslambda.v1_0

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.RequestHandler
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.context.propagation.TextMapPropagator
import io.opentelemetry.extension.trace.propagation.AwsXRayPropagator
import io.opentelemetry.instrumentation.test.InstrumentationTestTrait

class AwsLambdaTest extends AbstractAwsLambdaRequestHandlerTest implements InstrumentationTestTrait {

  // Lambda instrumentation requires XRay propagator to be enabled.
  static {
    def propagators = ContextPropagators.create(
      TextMapPropagator.composite(W3CTraceContextPropagator.instance, AwsXRayPropagator.instance))
    OpenTelemetry.setGlobalPropagators(propagators)
  }

  def cleanup() {
    assert forceFlushCalled()
  }

  static class TestRequestHandler extends TracingRequestHandler<String, String> {

    @Override
    protected String doHandleRequest(String input, Context context) {
      return AbstractAwsLambdaRequestHandlerTest.doHandleRequest(input, context)
    }
  }

  @Override
  RequestHandler<String, String> handler() {
    return new TestRequestHandler()
  }
}
