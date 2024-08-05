/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.apachedubbo.v2_7

import org.apache.dubbo.config.bootstrap.DubboBootstrap

class DubboTestUtil {
  static newFrameworkModel() {
    try {
      // only present in latest dep
      return Class.forName("org.apache.dubbo.rpc.model.FrameworkModel").newInstance()
    } catch (ClassNotFoundException exception) {
      return null
    }
  }

  static DubboBootstrap newDubboBootstrap(Object frameworkModel) {
    // compatible with 2.7.x
    if (frameworkModel == null) {
      return DubboBootstrap.newInstance()
    }
    // compatible with 3.0
    return DubboBootstrap.newInstance(frameworkModel)
  }
}
