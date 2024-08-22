/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.servlet.v2_2;

import static io.opentelemetry.instrumentation.testing.junit.http.ServerEndpoint.ERROR;
import static io.opentelemetry.instrumentation.testing.junit.http.ServerEndpoint.EXCEPTION;
import static io.opentelemetry.instrumentation.testing.junit.http.ServerEndpoint.INDEXED_CHILD;
import static io.opentelemetry.instrumentation.testing.junit.http.ServerEndpoint.QUERY_PARAM;
import static io.opentelemetry.instrumentation.testing.junit.http.ServerEndpoint.REDIRECT;
import static io.opentelemetry.instrumentation.testing.junit.http.ServerEndpoint.SUCCESS;

import io.opentelemetry.instrumentation.test.base.HttpServerTest;
import io.opentelemetry.instrumentation.testing.junit.http.ServerEndpoint;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class TestServlet2 {

  private TestServlet2() {}

  public static class Sync extends HttpServlet {
    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) {
      req.getRequestDispatcher(null);
      ServerEndpoint endpoint = ServerEndpoint.forPath(req.getServletPath());
      HttpServerTest.controller(
          endpoint,
          () -> {
            resp.setContentType("text/plain");
            if (endpoint.equals(SUCCESS)) {
              resp.setStatus(endpoint.getStatus());
              resp.getWriter().print(endpoint.getBody());
            } else if (endpoint.equals(QUERY_PARAM)) {
              resp.setStatus(endpoint.getStatus());
              resp.getWriter().print(req.getQueryString());
            } else if (endpoint.equals(REDIRECT)) {
              resp.sendRedirect(endpoint.getBody());
            } else if (endpoint.equals(ERROR)) {
              resp.sendError(endpoint.getStatus(), endpoint.getBody());
            } else if (endpoint.equals(EXCEPTION)) {
              throw new Exception(endpoint.getBody());
            } else if (endpoint.equals(INDEXED_CHILD)) {
              INDEXED_CHILD.collectSpanAttributes(req::getParameter);
              resp.setStatus(endpoint.getStatus());
              resp.getWriter().print(endpoint.getBody());
            }
            return null;
          });
    }
  }
}
