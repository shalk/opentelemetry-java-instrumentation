/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.servlet.v2_2;

import static io.opentelemetry.instrumentation.testing.junit.http.ServerEndpoint.CAPTURE_HEADERS;
import static io.opentelemetry.instrumentation.testing.junit.http.ServerEndpoint.ERROR;
import static io.opentelemetry.instrumentation.testing.junit.http.ServerEndpoint.EXCEPTION;
import static io.opentelemetry.instrumentation.testing.junit.http.ServerEndpoint.INDEXED_CHILD;
import static io.opentelemetry.instrumentation.testing.junit.http.ServerEndpoint.NOT_FOUND;
import static io.opentelemetry.instrumentation.testing.junit.http.ServerEndpoint.QUERY_PARAM;
import static io.opentelemetry.instrumentation.testing.junit.http.ServerEndpoint.REDIRECT;
import static io.opentelemetry.instrumentation.testing.junit.http.ServerEndpoint.SUCCESS;

import io.opentelemetry.instrumentation.testing.junit.InstrumentationExtension;
import io.opentelemetry.instrumentation.testing.junit.http.ServerEndpoint;
import java.io.IOException;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.Request;

public class TestServlet2 {

  private TestServlet2() {}

  public static class Sync extends HttpServlet {

    private final int port;
    private final InstrumentationExtension testing;

    public Sync(int port, InstrumentationExtension testing) {
      this.port = port;
      this.testing = testing;
    }

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse response)
        throws ServletException, IOException {
      req.getRequestDispatcher(null);
      System.out.println("path=" + req.getServletPath());
      ServerEndpoint endpoint = ServerEndpoint.forPath(req.getServletPath());

      response.setContentType("text/plain");
      if (req.getServletPath().equals(NOT_FOUND.getPath())) {
        response.setStatus(endpoint.getStatus());
        response.getWriter().print(endpoint.getBody());
        return;
      }
      testing.runWithSpan(
          "controller",
          () -> {
            if (SUCCESS.equals(endpoint)) {
              response.setStatus(endpoint.getStatus());
              response.getWriter().print(endpoint.getBody());
            } else if (QUERY_PARAM.equals(endpoint)) {
              response.setStatus(endpoint.getStatus());
              response.getWriter().print(((Request) req).getUri().getQuery());
            } else if (REDIRECT.equals(endpoint)) {
              response.setStatus(endpoint.getStatus());
              response.setHeader("Location", "http://localhost:" + port + endpoint.getBody());
            } else if (ERROR.equals(endpoint)) {
              response.setStatus(endpoint.getStatus());
              response.getWriter().print(endpoint.getBody());
            } else if (CAPTURE_HEADERS.equals(endpoint)) {
              response.setHeader("X-Test-Response", req.getHeader("X-Test-Request"));
              response.setStatus(endpoint.getStatus());
              response.getWriter().print(endpoint.getBody());
            } else if (EXCEPTION.equals(endpoint)) {
              throw new IllegalStateException(endpoint.getBody());
            } else if (INDEXED_CHILD.equals(endpoint)) {
              INDEXED_CHILD.collectSpanAttributes(req::getParameter);
              response.setStatus(endpoint.getStatus());
              response.getWriter().print(endpoint.getBody());
            }
          });
    }
  }
}
