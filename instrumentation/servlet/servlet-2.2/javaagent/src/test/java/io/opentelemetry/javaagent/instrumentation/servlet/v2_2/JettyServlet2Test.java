/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.servlet.v2_2;

import static io.opentelemetry.instrumentation.testing.junit.http.HttpServerTestOptions.DEFAULT_HTTP_ATTRIBUTES;
import static io.opentelemetry.instrumentation.testing.junit.http.ServerEndpoint.AUTH_REQUIRED;
import static io.opentelemetry.instrumentation.testing.junit.http.ServerEndpoint.ERROR;
import static io.opentelemetry.instrumentation.testing.junit.http.ServerEndpoint.EXCEPTION;
import static io.opentelemetry.instrumentation.testing.junit.http.ServerEndpoint.INDEXED_CHILD;
import static io.opentelemetry.instrumentation.testing.junit.http.ServerEndpoint.NOT_FOUND;
import static io.opentelemetry.instrumentation.testing.junit.http.ServerEndpoint.PATH_PARAM;
import static io.opentelemetry.instrumentation.testing.junit.http.ServerEndpoint.QUERY_PARAM;
import static io.opentelemetry.instrumentation.testing.junit.http.ServerEndpoint.REDIRECT;
import static io.opentelemetry.instrumentation.testing.junit.http.ServerEndpoint.SUCCESS;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.collect.Sets;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.instrumentation.api.internal.HttpConstants;
import io.opentelemetry.instrumentation.testing.junit.InstrumentationExtension;
import io.opentelemetry.instrumentation.testing.junit.http.AbstractHttpServerTest;
import io.opentelemetry.instrumentation.testing.junit.http.HttpServerInstrumentationExtension;
import io.opentelemetry.instrumentation.testing.junit.http.HttpServerTestOptions;
import io.opentelemetry.instrumentation.testing.junit.http.ServerEndpoint;
import io.opentelemetry.sdk.testing.assertj.SpanDataAssert;
import io.opentelemetry.semconv.HttpAttributes;
import java.io.IOException;
import java.io.Writer;
import java.util.Collections;
import java.util.Objects;
import javax.servlet.http.HttpServletRequest;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ErrorHandler;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.extension.RegisterExtension;

class JettyServlet2Test extends AbstractHttpServerTest<Server> {

  @RegisterExtension
  static final InstrumentationExtension testing = HttpServerInstrumentationExtension.forAgent();

  @Override
  protected Server setupServer() throws Exception {
    Server server = new Server(port);
    for (Connector connector : server.getConnectors()) {
      connector.setHost("localhost");
    }
    ServletContextHandler servletContext = createServletContext();

    // FIXME: Add tests for security/authentication.
    //    ConstraintSecurityHandler security = setupAuthentication(jettyServer)
    //    servletContext.setSecurityHandler(security)

    servletContext.addServlet(TestServlet2.Sync.class, SUCCESS.getPath());
    servletContext.addServlet(TestServlet2.Sync.class, QUERY_PARAM.getPath());
    servletContext.addServlet(TestServlet2.Sync.class, REDIRECT.getPath());
    servletContext.addServlet(TestServlet2.Sync.class, ERROR.getPath());
    servletContext.addServlet(TestServlet2.Sync.class, EXCEPTION.getPath());
    servletContext.addServlet(TestServlet2.Sync.class, AUTH_REQUIRED.getPath());
    servletContext.addServlet(TestServlet2.Sync.class, INDEXED_CHILD.getPath());

    server.setHandler(servletContext);
    server.start();
    return server;
  }

  private static @NotNull ServletContextHandler createServletContext() {
    ServletContextHandler servletContext = new ServletContextHandler(null, "/");
    servletContext.setErrorHandler(
        new ErrorHandler() {
          @Override
          protected void handleErrorPage(
              HttpServletRequest request, Writer writer, int code, String message)
              throws IOException {
            Throwable th = (Throwable) request.getAttribute("javax.servlet.error.exception");
            writer.write(th != null ? th.getMessage() : message);
          }
        });
    return servletContext;
  }

  @Override
  protected void stopServer(Server server) throws Exception {
    server.stop();
    server.destroy();
  }

  @Override
  protected void configure(HttpServerTestOptions options) {
    options.setHttpAttributes(
        unused ->
            Sets.difference(
                DEFAULT_HTTP_ATTRIBUTES, Collections.singleton(HttpAttributes.HTTP_ROUTE)));
    options.setExpectedException(new IllegalStateException(EXCEPTION.getBody()));
    options.setHasResponseCustomizer(endpoint -> endpoint != EXCEPTION);
  }

  @Override
  protected SpanDataAssert assertResponseSpan(
      SpanDataAssert span, String method, ServerEndpoint endpoint) {
    if (endpoint == REDIRECT) {
      span.satisfies(spanData -> assertThat(spanData.getName()).endsWith(".sendRedirect"));
    } else if (endpoint == ERROR) {
      span.satisfies(spanData -> assertThat(spanData.getName()).endsWith(".sendError"));
    }
    span.hasKind(SpanKind.INTERNAL).hasAttributesSatisfying(Attributes::isEmpty);
    return span;
  }

  //  @Override

  @Override
  public String expectedHttpRoute(ServerEndpoint endpoint, String method) {
    if (Objects.equals(method, HttpConstants._OTHER)) {
      return "HTTP " + address.resolve(endpoint.getPath()).getPath();
    }
    if (endpoint.equals(NOT_FOUND)) {
      return method;
    } else if (endpoint.equals(PATH_PARAM)) {
      return method + " " + getContextPath() + "/path/:id/param";
    }
    return address.resolve(endpoint.getPath()).getPath();
  }

  //
  //  @Override
  //  boolean testNotFound() {
  //    false
  //  }
  //
  //  // servlet 2 does not expose a way to retrieve response headers
  //  @Override
  //  boolean testCapturedHttpHeaders() {
  //    false
  //  }
  //
  //  @Override
  //  boolean hasResponseSpan(ServerEndpoint endpoint) {
  //    endpoint == REDIRECT || endpoint == ERROR
  //  }
  //
  //  @Override
  //  boolean hasResponseCustomizer(ServerEndpoint endpoint) {
  //    true
  //  }
  //
  //  @Override
  //  void responseSpan(TraceAssert trace, int index, Object parent, String method = "GET",
  // ServerEndpoint endpoint = SUCCESS) {
  //    def responseMethod = endpoint == REDIRECT ? "sendRedirect" : "sendError"
  //    trace.span(index) {
  //      name "Response.$responseMethod"
  //      kind INTERNAL
  //      childOf((SpanData) parent)
  //      attributes {
  //        "$CodeIncubatingAttributes.CODE_NAMESPACE" Response.name
  //        "$CodeIncubatingAttributes.CODE_FUNCTION" responseMethod
  //      }
  //    }
  //  }

  public static void main(String[] args) throws Exception {
    JettyServlet2Test jettyServlet2Test = new JettyServlet2Test();
    Server server = jettyServlet2Test.setupServer();
    server.isStarted();
    System.in.read();
  }
}
