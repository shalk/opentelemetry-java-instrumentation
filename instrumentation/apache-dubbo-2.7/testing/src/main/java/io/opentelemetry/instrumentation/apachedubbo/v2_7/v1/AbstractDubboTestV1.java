/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.apachedubbo.v2_7.v1;

import static io.opentelemetry.instrumentation.testing.GlobalTraceUtil.runWithSpan;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.opentelemetry.instrumentation.apachedubbo.v2_7.api.HelloService;
import io.opentelemetry.instrumentation.apachedubbo.v2_7.impl.HelloServiceImpl;
import io.opentelemetry.instrumentation.test.utils.PortUtils;
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.util.List;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.ProtocolConfig;
import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.config.ServiceConfig;
import org.apache.dubbo.config.bootstrap.DubboBootstrap;
import org.apache.dubbo.rpc.service.GenericService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.RegisterExtension;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public  class AbstractDubboTestV1 {

  @RegisterExtension
  static final OpenTelemetryExtension otelTesting = OpenTelemetryExtension.create();
  private final ProtocolConfig protocolConfig = new ProtocolConfig();

  @BeforeAll
  void setUp() throws Exception {
    Field field = NetUtils.class.getDeclaredField("LOCAL_ADDRESS");
    field.setAccessible(true);
    field.set(null, InetAddress.getLoopbackAddress());
  }

  ReferenceConfig<HelloService> configureClient(int port) {
    ReferenceConfig<HelloService> reference = new ReferenceConfig<>();
    reference.setInterface(HelloService.class);
    reference.setGeneric("true");
    reference.setUrl("dubbo://localhost:" + port + "/?timeout=30000");
    return reference;
  }

  ServiceConfig<HelloServiceImpl> configureServer() {
    RegistryConfig registerConfig = new RegistryConfig();
    registerConfig.setAddress("N/A");
    ServiceConfig<HelloServiceImpl> service = new ServiceConfig<>();
    service.setInterface(HelloService.class);
    service.setRef(new HelloServiceImpl());
    service.setRegistry(registerConfig);
    return service;
  }

  @Test
  void testApacheDubboBase() throws Exception {
    int port = PortUtils.findOpenPort();
    protocolConfig.setPort(port);
    // provider boostrap
    DubboBootstrap bootstrap = DubboBootstrap.getInstance();
    bootstrap.application(new ApplicationConfig("dubbo-test-provider"))
        .service(configureServer())
        .protocol(protocolConfig)
        .start();

    // consumer boostrap
    DubboBootstrap consumerBootstrap = DubboTestUtilV1.newDubboBootstrap(DubboTestUtilV1.newFrameworkModel());
    ReferenceConfig<HelloService> referenceConfig = configureClient(port);
    ProtocolConfig consumerProtocolConfig = new ProtocolConfig();
    consumerProtocolConfig.setRegister(false);
    consumerBootstrap.application(new ApplicationConfig("dubbo-demo-api-consumer"))
        .reference(referenceConfig)
        .protocol(consumerProtocolConfig)
        .start();

    // generic call
    ReferenceConfig<GenericService> reference = new ReferenceConfig<>();
    reference.setInterface("io.opentelemetry.instrumentation.apachedubbo.v2_7.api.HelloService");
    reference.setGeneric("true");
    GenericService genericService = reference.get();

    Object[] o = new Object[1];
    o[0] = "hello";
    Object response = runWithSpan("parent", () -> genericService.$invoke("hello", new String[]{String.class.getName()}, o));

    assertEquals("hello", response);
    List<SpanData> spans1 = otelTesting.getSpans();
    System.out.println("spans1 = " + spans1);
//    otelTesting.assertTraces().allMatch( trace -> {
//      trace.trace(0, 3, spans -> {
//        spans.span(0, span -> {
//          span.name("parent");
//          span.kind(SpanKind.INTERNAL);
//          span.hasNoParent();
//        });
//        spans.span(1, span -> {
//          span.name("org.apache.dubbo.rpc.service.GenericService/$invoke");
//          span.kind(CLIENT);
//          span.childOf(0);
//          span.attributes(attr -> {
//            attr.put(RpcIncubatingAttributes.RPC_SYSTEM, "apache_dubbo");
//            attr.put(RpcIncubatingAttributes.RPC_SERVICE, "org.apache.dubbo.rpc.service.GenericService");
//            attr.put(RpcIncubatingAttributes.RPC_METHOD, "$invoke");
//            attr.put(ServerAttributes.SERVER_ADDRESS, "localhost");
//            attr.put(ServerAttributes.SERVER_PORT, Long.class);
//            attr.put(NetworkAttributes.NETWORK_PEER_ADDRESS, (Object) null);
//            attr.put(NetworkAttributes.NETWORK_PEER_PORT, (Object) null);
//            attr.put(NetworkAttributes.NETWORK_TYPE, (Object) null);
//          });
//        });
//        spans.span(2, span -> {
//          span.name("io.opentelemetry.instrumentation.apachedubbo.v2_7.api.HelloService/hello");
//          span.kind(SERVER);
//          span.childOf(1);
//          span.attributes(attr -> {
//            attr.put(RpcIncubatingAttributes.RPC_SYSTEM, "apache_dubbo");
//            attr.put(RpcIncubatingAttributes.RPC_SERVICE, "io.opentelemetry.instrumentation.apachedubbo.v2_7.api.HelloService");
//            attr.put(RpcIncubatingAttributes.RPC_METHOD, "hello");
//            attr.put(NetworkAttributes.NETWORK_PEER_ADDRESS, String.class);
//            attr.put(NetworkAttributes.NETWORK_PEER_PORT, Long.class);
//            attr.put(NetworkAttributes.NETWORK_TYPE, (Object) null);
//          });
//        });
//      });
//    });

    bootstrap.destroy();
    consumerBootstrap.destroy();
  }

//  @Test
//  void testApacheDubboTest() throws Exception {
//    int port = PortUtils.findOpenPort();
//    protocolConfig.setPort(port);
//
//    var frameworkModel = newFrameworkModel();
//    DubboBootstrap bootstrap = newDubboBootstrap(frameworkModel);
//    bootstrap.application(new ApplicationConfig("dubbo-test-async-provider"))
//        .service(configureServer())
//        .protocol(protocolConfig)
//        .start();
//
//    ProtocolConfig consumerProtocolConfig = new ProtocolConfig();
//    consumerProtocolConfig.setRegister(false);
//
//    ReferenceConfig<HelloService> reference = configureClient(port);
//    DubboBootstrap consumerBootstrap = newDubboBootstrap(frameworkModel);
//    consumerBootstrap.application(new ApplicationConfig("dubbo-demo-async-api-consumer"))
//        .reference(reference)
//        .protocol(consumerProtocolConfig)
//        .start();
//
//    GenericService genericService = reference.get();
//    Object[] o = new Object[1];
//    o[0] = "hello";
//    var responseAsync = runWithSpan("parent", () -> genericService.$invokeAsync("hello", new String[]{String.class.getName()}, o));
//
//    assertEquals("hello", responseAsync.get());
//    assertTraces(1, trace -> {
//      trace.trace(0, 3, spans -> {
//        spans.span(0, span -> {
//          span.name("parent");
//          span.kind(SpanKind.INTERNAL);
//          span.hasNoParent();
//        });
//        spans.span(1, span -> {
//          span.name("org.apache.dubbo.rpc.service.GenericService/$invokeAsync");
//          span.kind(CLIENT);
//          span.childOf(0);
//          span.attributes(attr -> {
//            attr.put(RpcIncubatingAttributes.RPC_SYSTEM, "apache_dubbo");
//            attr.put(RpcIncubatingAttributes.RPC_SERVICE, "org.apache.dubbo.rpc.service.GenericService");
//            attr.put(RpcIncubatingAttributes.RPC_METHOD, "$invokeAsync");
//            attr.put(ServerAttributes.SERVER_ADDRESS, "localhost");
//            attr.put(ServerAttributes.SERVER_PORT, Long.class);
//            attr.put(NetworkAttributes.NETWORK_PEER_ADDRESS, (Object) null);
//            attr.put(NetworkAttributes.NETWORK_PEER_PORT, (Object) null);
//            attr.put(NetworkAttributes.NETWORK_TYPE, (Object) null);
//          });
//        });
//        spans.span(2, span -> {
//          span.name("io.opentelemetry.instrumentation.apachedubbo.v2_7.api.HelloService/hello");
//          span.kind(SERVER);
//          span.childOf(1);
//          span.attributes(attr -> {
//            attr.put(RpcIncubatingAttributes.RPC_SYSTEM, "apache_dubbo");
//            attr.put(RpcIncubatingAttributes.RPC_SERVICE, "io.opentelemetry.instrumentation.apachedubbo.v2_7.api.HelloService");
//            attr.put(RpcIncubatingAttributes.RPC_METHOD, "hello");
//            attr.put(NetworkAttributes.NETWORK_PEER_ADDRESS, String.class);
//            attr.put(NetworkAttributes.NETWORK_PEER_PORT, Long.class);
//            attr.put(NetworkAttributes.NETWORK_TYPE, (Object) null);
//          });
//        });
//      });
//    });
//
//    bootstrap.destroy();
//    consumerBootstrap.destroy();
//    frameworkModel.destroy();
//  }
}
