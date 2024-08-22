/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

import io.opentelemetry.instrumentation.testing.GlobalTraceUtil
import io.opentelemetry.instrumentation.testing.junit.http.ServerEndpoint

import javax.servlet.http.HttpServlet
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import java.util.concurrent.Callable

import static io.opentelemetry.instrumentation.testing.junit.http.ServerEndpoint.NOT_FOUND

class TestServlet2 {

  static class Sync extends HttpServlet {
    public static <T> T controller(ServerEndpoint serverEndpoint,Callable<T> closure) {
      if (serverEndpoint == NOT_FOUND) {
        return closure.call()
      }
      return GlobalTraceUtil.runWithSpan("controller") {
        closure.call()
      }
    }
    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) {
      req.getRequestDispatcher()
      ServerEndpoint endpoint = ServerEndpoint.forPath(req.servletPath)
      controller(endpoint) {
        resp.contentType = "text/plain"
      }
    }
  }
}
