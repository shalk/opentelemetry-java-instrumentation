/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.jdbc.test;

import static io.opentelemetry.sdk.testing.assertj.OpenTelemetryAssertions.equalTo;
import static io.opentelemetry.sdk.testing.assertj.OpenTelemetryAssertions.satisfies;
import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import com.mchange.v2.c3p0.ComboPooledDataSource;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.instrumentation.jdbc.TestConnection;
import io.opentelemetry.instrumentation.jdbc.TestDriver;
import io.opentelemetry.instrumentation.testing.internal.AutoCleanupExtension;
import io.opentelemetry.instrumentation.testing.junit.AgentInstrumentationExtension;
import io.opentelemetry.instrumentation.testing.junit.InstrumentationExtension;
import io.opentelemetry.sdk.testing.assertj.TraceAssert;
import io.opentelemetry.semconv.ServerAttributes;
import io.opentelemetry.semconv.incubating.CodeIncubatingAttributes;
import io.opentelemetry.semconv.incubating.DbIncubatingAttributes;
import java.beans.PropertyVetoException;
import java.io.Closeable;
import java.io.IOException;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.apache.derby.jdbc.EmbeddedDataSource;
import org.apache.derby.jdbc.EmbeddedDriver;
import org.h2.jdbcx.JdbcDataSource;
import org.hsqldb.jdbc.JDBCDriver;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class JdbcInstrumentationTest {

  @RegisterExtension static AutoCleanupExtension cleanup = AutoCleanupExtension.create();

  @RegisterExtension
  static InstrumentationExtension testing = AgentInstrumentationExtension.create();

  private static String dbName;
  private static String dbNameLower;
  private static Map<String, String> jdbcUrls;
  private static Map<String, String> jdbcDriverClassNames;
  private static Map<String, String> jdbcUserNames;
  private static Properties connectionProps;
  // JDBC Connection pool name (i.e. HikariCP) -> Map<dbName, Datasource>
  private static Map<String, Map<String, DataSource>> cpDatasources;

  @BeforeAll
  static void setUp() {
    dbName = "jdbcUnitTest";
    dbNameLower = dbName.toLowerCase(Locale.ROOT);
    jdbcUrls =
        Collections.unmodifiableMap(
            Stream.of(
                    entry("h2", "jdbc:h2:mem:" + dbName),
                    entry("derby", "jdbc:derby:memory:" + dbName),
                    entry("hsqldb", "jdbc:hsqldb:mem:" + dbName))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));

    jdbcDriverClassNames =
        Collections.unmodifiableMap(
            Stream.of(
                    entry("h2", "org.h2.Driver"),
                    entry("derby", "org.apache.derby.jdbc.EmbeddedDriver"),
                    entry("hsqldb", "org.hsqldb.jdbc.JDBCDriver"))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));

    jdbcUserNames = new HashMap<>();
    jdbcUserNames.put("derby", "APP");
    jdbcUserNames.put("h2", null);
    jdbcUserNames.put("hsqldb", "SA");

    connectionProps = new Properties();
    connectionProps.put("databaseName", "someDb");
    connectionProps.put("OPEN_NEW", "true"); // So H2 doesn't complain about username/password.

    cpDatasources = new HashMap<>();

    prepareConnectionPoolDatasources();
  }

  @AfterAll
  static void tearDown() {
    cpDatasources
        .values()
        .forEach(
            k ->
                k.values()
                    .forEach(
                        dataSource -> {
                          if (dataSource instanceof Closeable) {
                            try {
                              ((Closeable) dataSource).close();
                            } catch (IOException ignore) {
                              // ignore
                            }
                          }
                        }));
  }

  static void prepareConnectionPoolDatasources() {
    List<String> connectionPoolNames = asList("tomcat", "hikari", "c3p0");
    connectionPoolNames.forEach(
        cpName -> {
          Map<String, DataSource> dbDsMapping = new HashMap<>();
          jdbcUrls.forEach(
              (dbType, jdbcUrl) -> dbDsMapping.put(dbType, createDs(cpName, dbType, jdbcUrl)));
          cpDatasources.put(cpName, dbDsMapping);
        });
  }

  static DataSource createTomcatDs(String dbType, String jdbcUrl) {
    org.apache.tomcat.jdbc.pool.DataSource ds = new org.apache.tomcat.jdbc.pool.DataSource();
    String jdbcUrlToSet = Objects.equals(dbType, "derby") ? jdbcUrl + ";create=true" : jdbcUrl;
    ds.setUrl(jdbcUrlToSet);
    ds.setDriverClassName(jdbcDriverClassNames.get(dbType));
    String username = jdbcUserNames.get(dbType);
    if (username != null) {
      ds.setUsername(username);
    }
    ds.setPassword("");
    ds.setMaxActive(1); // to test proper caching, having > 1 max active connection will be hard to
    // determine whether the connection is properly cached
    return ds;
  }

  static DataSource createHikariDs(String dbType, String jdbcUrl) {
    HikariConfig config = new HikariConfig();
    String jdbcUrlToSet = Objects.equals(dbType, "derby") ? jdbcUrl + ";create=true" : jdbcUrl;
    config.setJdbcUrl(jdbcUrlToSet);
    String username = jdbcUserNames.get(dbType);
    if (username != null) {
      config.setUsername(username);
    }
    config.setPassword("");
    config.addDataSourceProperty("cachePrepStmts", "true");
    config.addDataSourceProperty("prepStmtCacheSize", "250");
    config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
    config.setMaximumPoolSize(1);

    return new HikariDataSource(config);
  }

  static DataSource createC3P0Ds(String dbType, String jdbcUrl) {
    ComboPooledDataSource ds = new ComboPooledDataSource();
    try {
      ds.setDriverClass(jdbcDriverClassNames.get(dbType));
    } catch (PropertyVetoException e) {
      throw new RuntimeException(e);
    }
    String jdbcUrlToSet = Objects.equals(dbType, "derby") ? jdbcUrl + ";create=true" : jdbcUrl;
    ds.setJdbcUrl(jdbcUrlToSet);
    String username = jdbcUserNames.get(dbType);
    if (username != null) {
      ds.setUser(username);
    }
    ds.setPassword("");
    ds.setMaxPoolSize(1);
    return ds;
  }

  static DataSource createDs(String connectionPoolName, String dbType, String jdbcUrl) {
    DataSource ds = null;
    if (Objects.equals(connectionPoolName, "tomcat")) {
      ds = createTomcatDs(dbType, jdbcUrl);
    }
    if (Objects.equals(connectionPoolName, "hikari")) {
      ds = createHikariDs(dbType, jdbcUrl);
    }
    if (Objects.equals(connectionPoolName, "c3p0")) {
      ds = createC3P0Ds(dbType, jdbcUrl);
    }
    return ds;
  }

  static Stream<Arguments> basicStatementStream() throws SQLException {
    return Stream.of(
        Arguments.of(
            "h2",
            new org.h2.Driver().connect(jdbcUrls.get("h2"), null),
            null,
            "SELECT 3",
            "SELECT ?",
            "SELECT " + dbNameLower,
            "h2:mem:",
            null),
        Arguments.of(
            "derby",
            new EmbeddedDriver().connect(jdbcUrls.get("derby"), null),
            "APP",
            "SELECT 3 FROM SYSIBM.SYSDUMMY1",
            "SELECT ? FROM SYSIBM.SYSDUMMY1",
            "SELECT SYSIBM.SYSDUMMY1",
            "derby:memory:",
            "SYSIBM.SYSDUMMY1"),
        Arguments.of(
            "hsqldb",
            new JDBCDriver().connect(jdbcUrls.get("hsqldb"), null),
            "SA",
            "SELECT 3 FROM INFORMATION_SCHEMA.SYSTEM_USERS",
            "SELECT ? FROM INFORMATION_SCHEMA.SYSTEM_USERS",
            "SELECT INFORMATION_SCHEMA.SYSTEM_USERS",
            "hsqldb:mem:",
            "INFORMATION_SCHEMA.SYSTEM_USERS"),
        Arguments.of(
            "h2",
            new org.h2.Driver().connect(jdbcUrls.get("h2"), connectionProps),
            null,
            "SELECT 3",
            "SELECT ?",
            "SELECT " + dbNameLower,
            "h2:mem:",
            null),
        Arguments.of(
            "derby",
            new EmbeddedDriver().connect(jdbcUrls.get("derby"), connectionProps),
            "APP",
            "SELECT 3 FROM SYSIBM.SYSDUMMY1",
            "SELECT ? FROM SYSIBM.SYSDUMMY1",
            "SELECT SYSIBM.SYSDUMMY1",
            "derby:memory:",
            "SYSIBM.SYSDUMMY1"),
        Arguments.of(
            "hsqldb",
            new JDBCDriver().connect(jdbcUrls.get("hsqldb"), connectionProps),
            "SA",
            "SELECT 3 FROM INFORMATION_SCHEMA.SYSTEM_USERS",
            "SELECT ? FROM INFORMATION_SCHEMA.SYSTEM_USERS",
            "SELECT INFORMATION_SCHEMA.SYSTEM_USERS",
            "hsqldb:mem:",
            "INFORMATION_SCHEMA.SYSTEM_USERS"),
        Arguments.of(
            "h2",
            cpDatasources.get("tomcat").get("h2").getConnection(),
            null,
            "SELECT 3",
            "SELECT ?",
            "SELECT " + dbNameLower,
            "h2:mem:",
            null),
        Arguments.of(
            "derby",
            cpDatasources.get("tomcat").get("derby").getConnection(),
            "APP",
            "SELECT 3 FROM SYSIBM.SYSDUMMY1",
            "SELECT ? FROM SYSIBM.SYSDUMMY1",
            "SELECT SYSIBM.SYSDUMMY1",
            "derby:memory:",
            "SYSIBM.SYSDUMMY1"),
        Arguments.of(
            "hsqldb",
            cpDatasources.get("tomcat").get("hsqldb").getConnection(),
            "SA",
            "SELECT 3 FROM INFORMATION_SCHEMA.SYSTEM_USERS",
            "SELECT ? FROM INFORMATION_SCHEMA.SYSTEM_USERS",
            "SELECT INFORMATION_SCHEMA.SYSTEM_USERS",
            "hsqldb:mem:",
            "INFORMATION_SCHEMA.SYSTEM_USERS"),
        Arguments.of(
            "h2",
            cpDatasources.get("hikari").get("h2").getConnection(),
            null,
            "SELECT 3",
            "SELECT ?",
            "SELECT " + dbNameLower,
            "h2:mem:",
            null),
        Arguments.of(
            "derby",
            cpDatasources.get("hikari").get("derby").getConnection(),
            "APP",
            "SELECT 3 FROM SYSIBM.SYSDUMMY1",
            "SELECT ? FROM SYSIBM.SYSDUMMY1",
            "SELECT SYSIBM.SYSDUMMY1",
            "derby:memory:",
            "SYSIBM.SYSDUMMY1"),
        Arguments.of(
            "hsqldb",
            cpDatasources.get("hikari").get("hsqldb").getConnection(),
            "SA",
            "SELECT 3 FROM INFORMATION_SCHEMA.SYSTEM_USERS",
            "SELECT ? FROM INFORMATION_SCHEMA.SYSTEM_USERS",
            "SELECT INFORMATION_SCHEMA.SYSTEM_USERS",
            "hsqldb:mem:",
            "INFORMATION_SCHEMA.SYSTEM_USERS"),
        Arguments.of(
            "h2",
            cpDatasources.get("c3p0").get("h2").getConnection(),
            null,
            "SELECT 3",
            "SELECT ?",
            "SELECT " + dbNameLower,
            "h2:mem:",
            null),
        Arguments.of(
            "derby",
            cpDatasources.get("c3p0").get("derby").getConnection(),
            "APP",
            "SELECT 3 FROM SYSIBM.SYSDUMMY1",
            "SELECT ? FROM SYSIBM.SYSDUMMY1",
            "SELECT SYSIBM.SYSDUMMY1",
            "derby:memory:",
            "SYSIBM.SYSDUMMY1"),
        Arguments.of(
            "hsqldb",
            cpDatasources.get("c3p0").get("hsqldb").getConnection(),
            "SA",
            "SELECT 3 FROM INFORMATION_SCHEMA.SYSTEM_USERS",
            "SELECT ? FROM INFORMATION_SCHEMA.SYSTEM_USERS",
            "SELECT INFORMATION_SCHEMA.SYSTEM_USERS",
            "hsqldb:mem:",
            "INFORMATION_SCHEMA.SYSTEM_USERS"));
  }

  @SuppressWarnings("deprecation") // TODO DbIncubatingAttributes.DB_CONNECTION_STRING deprecation
  @DisplayName(
      "basic statement with #connection.getClass().getCanonicalName() on #system generates spans")
  @ParameterizedTest
  @MethodSource("basicStatementStream")
  public void testBasicStatement(
      String system,
      Connection connection,
      String username,
      String query,
      String sanitizedQuery,
      String spanName,
      String url,
      String table)
      throws SQLException {
    Statement statement = connection.createStatement();
    ResultSet resultSet = testing.runWithSpan("parent", () -> statement.executeQuery(query));

    resultSet.next();
    assertThat(resultSet.getInt(1)).isEqualTo(3);

    testing.waitAndAssertTraces(
        trace ->
            trace.hasSpansSatisfyingExactly(
                span -> span.hasName("parent").hasKind(SpanKind.INTERNAL).hasNoParent(),
                span ->
                    span.hasName(spanName)
                        .hasKind(SpanKind.CLIENT)
                        .hasParent(trace.getSpan(0))
                        .hasAttributesSatisfyingExactly(
                            equalTo(DbIncubatingAttributes.DB_SYSTEM, system),
                            equalTo(DbIncubatingAttributes.DB_NAME, dbNameLower),
                            satisfies(
                                DbIncubatingAttributes.DB_USER,
                                val ->
                                    val.satisfiesAnyOf(
                                        v -> assertThat(v).isEqualTo(username),
                                        v -> assertThat(v).isNull())),
                            equalTo(getDbConnectionStringKey(), url),
                            equalTo(DbIncubatingAttributes.DB_STATEMENT, sanitizedQuery),
                            equalTo(DbIncubatingAttributes.DB_OPERATION, "SELECT"),
                            equalTo(DbIncubatingAttributes.DB_SQL_TABLE, table))));
    statement.close();
    connection.close();
  }

  static Stream<Arguments> preparedStatementStream() throws SQLException {
    return Stream.of(
        Arguments.of(
            "h2",
            new org.h2.Driver().connect(jdbcUrls.get("h2"), null),
            null,
            "SELECT 3",
            "SELECT ?",
            "SELECT " + dbNameLower,
            "h2:mem:",
            null),
        Arguments.of(
            "derby",
            new EmbeddedDriver().connect(jdbcUrls.get("derby"), null),
            "APP",
            "SELECT 3 FROM SYSIBM.SYSDUMMY1",
            "SELECT ? FROM SYSIBM.SYSDUMMY1",
            "SELECT SYSIBM.SYSDUMMY1",
            "derby:memory:",
            "SYSIBM.SYSDUMMY1"),
        Arguments.of(
            "h2",
            cpDatasources.get("tomcat").get("h2").getConnection(),
            null,
            "SELECT 3",
            "SELECT ?",
            "SELECT " + dbNameLower,
            "h2:mem:",
            null),
        Arguments.of(
            "derby",
            cpDatasources.get("tomcat").get("derby").getConnection(),
            "APP",
            "SELECT 3 FROM SYSIBM.SYSDUMMY1",
            "SELECT ? FROM SYSIBM.SYSDUMMY1",
            "SELECT SYSIBM.SYSDUMMY1",
            "derby:memory:",
            "SYSIBM.SYSDUMMY1"),
        Arguments.of(
            "h2",
            cpDatasources.get("hikari").get("h2").getConnection(),
            null,
            "SELECT 3",
            "SELECT ?",
            "SELECT " + dbNameLower,
            "h2:mem:",
            null),
        Arguments.of(
            "derby",
            cpDatasources.get("hikari").get("derby").getConnection(),
            "APP",
            "SELECT 3 FROM SYSIBM.SYSDUMMY1",
            "SELECT ? FROM SYSIBM.SYSDUMMY1",
            "SELECT SYSIBM.SYSDUMMY1",
            "derby:memory:",
            "SYSIBM.SYSDUMMY1"),
        Arguments.of(
            "h2",
            cpDatasources.get("c3p0").get("h2").getConnection(),
            null,
            "SELECT 3",
            "SELECT ?",
            "SELECT " + dbNameLower,
            "h2:mem:",
            null),
        Arguments.of(
            "derby",
            cpDatasources.get("c3p0").get("derby").getConnection(),
            "APP",
            "SELECT 3 FROM SYSIBM.SYSDUMMY1",
            "SELECT ? FROM SYSIBM.SYSDUMMY1",
            "SELECT SYSIBM.SYSDUMMY1",
            "derby:memory:",
            "SYSIBM.SYSDUMMY1"));
  }

  @ParameterizedTest
  @MethodSource("preparedStatementStream")
  @DisplayName(
      "prepared statement execute on #system with #connection.getClass().getCanonicalName() generates a span")
  void testPreparedStatementExecute(
      String system,
      Connection connection,
      String username,
      String query,
      String sanitizedQuery,
      String spanName,
      String url,
      String table)
      throws SQLException {
    PreparedStatement statement = connection.prepareStatement(query);
    cleanup.deferCleanup(statement);
    cleanup.deferCleanup(connection);
    ResultSet resultSet =
        testing.runWithSpan(
            "parent",
            () -> {
              statement.execute();
              return statement.getResultSet();
            });

    resultSet.next();
    assertThat(resultSet.getInt(1)).isEqualTo(3);

    testing.waitAndAssertTraces(
        trace ->
            trace.hasSpansSatisfyingExactly(
                span -> span.hasName("parent").hasKind(SpanKind.INTERNAL).hasNoParent(),
                span ->
                    span.hasName(spanName)
                        .hasKind(SpanKind.CLIENT)
                        .hasParent(trace.getSpan(0))
                        .hasAttributesSatisfying(
                            equalTo(DbIncubatingAttributes.DB_SYSTEM, system),
                            equalTo(DbIncubatingAttributes.DB_NAME, dbNameLower),
                            satisfies(
                                DbIncubatingAttributes.DB_USER,
                                val ->
                                    val.satisfiesAnyOf(
                                        v -> assertThat(v).isEqualTo(username),
                                        v -> assertThat(v).isNull())),
                            equalTo(getDbConnectionStringKey(), url),
                            equalTo(DbIncubatingAttributes.DB_STATEMENT, sanitizedQuery),
                            equalTo(DbIncubatingAttributes.DB_OPERATION, "SELECT"),
                            equalTo(DbIncubatingAttributes.DB_SQL_TABLE, table))));
  }

  @ParameterizedTest
  @MethodSource("preparedStatementStream")
  @DisplayName(
      "prepared statement query on #system with #connection.getClass().getCanonicalName() generates a span")
  void testPreparedStatementQuery(
      String system,
      Connection connection,
      String username,
      String query,
      String sanitizedQuery,
      String spanName,
      String url,
      String table)
      throws SQLException {
    PreparedStatement statement = connection.prepareStatement(query);
    cleanup.deferCleanup(statement);
    cleanup.deferCleanup(connection);
    ResultSet resultSet = testing.runWithSpan("parent", () -> statement.executeQuery());

    resultSet.next();
    assertThat(resultSet.getInt(1)).isEqualTo(3);

    testing.waitAndAssertTraces(
        trace ->
            trace.hasSpansSatisfyingExactly(
                span -> span.hasName("parent").hasKind(SpanKind.INTERNAL).hasNoParent(),
                span ->
                    span.hasName(spanName)
                        .hasKind(SpanKind.CLIENT)
                        .hasParent(trace.getSpan(0))
                        .hasAttributesSatisfying(
                            equalTo(DbIncubatingAttributes.DB_SYSTEM, system),
                            equalTo(DbIncubatingAttributes.DB_NAME, dbNameLower),
                            satisfies(
                                DbIncubatingAttributes.DB_USER,
                                val ->
                                    val.satisfiesAnyOf(
                                        v -> assertThat(v).isEqualTo(username),
                                        v -> assertThat(v).isNull())),
                            equalTo(getDbConnectionStringKey(), url),
                            equalTo(DbIncubatingAttributes.DB_STATEMENT, sanitizedQuery),
                            equalTo(DbIncubatingAttributes.DB_OPERATION, "SELECT"),
                            equalTo(DbIncubatingAttributes.DB_SQL_TABLE, table))));
  }

  @ParameterizedTest
  @MethodSource("preparedStatementStream")
  @DisplayName(
      "prepared call on #system with #connection.getClass().getCanonicalName() generates a span")
  void testPreparedCall(
      String system,
      Connection connection,
      String username,
      String query,
      String sanitizedQuery,
      String spanName,
      String url,
      String table)
      throws SQLException {
    CallableStatement statement = connection.prepareCall(query);
    cleanup.deferCleanup(statement);
    cleanup.deferCleanup(connection);
    ResultSet resultSet = testing.runWithSpan("parent", () -> statement.executeQuery());

    resultSet.next();
    assertThat(resultSet.getInt(1)).isEqualTo(3);

    testing.waitAndAssertTraces(
        trace ->
            trace.hasSpansSatisfyingExactly(
                span -> span.hasName("parent").hasKind(SpanKind.INTERNAL).hasNoParent(),
                span ->
                    span.hasName(spanName)
                        .hasKind(SpanKind.CLIENT)
                        .hasParent(trace.getSpan(0))
                        .hasAttributesSatisfying(
                            equalTo(DbIncubatingAttributes.DB_SYSTEM, system),
                            equalTo(DbIncubatingAttributes.DB_NAME, dbNameLower),
                            satisfies(
                                DbIncubatingAttributes.DB_USER,
                                val ->
                                    val.satisfiesAnyOf(
                                        v -> assertThat(v).isEqualTo(username),
                                        v -> assertThat(v).isNull())),
                            equalTo(getDbConnectionStringKey(), url),
                            equalTo(DbIncubatingAttributes.DB_STATEMENT, sanitizedQuery),
                            equalTo(DbIncubatingAttributes.DB_OPERATION, "SELECT"),
                            equalTo(DbIncubatingAttributes.DB_SQL_TABLE, table))));
  }

  static Stream<Arguments> statementUpdateStream() throws SQLException {
    return Stream.of(
        Arguments.of(
            "h2",
            new org.h2.Driver().connect(jdbcUrls.get("h2"), null),
            null,
            "CREATE TABLE S_H2 (id INTEGER not NULL, PRIMARY KEY ( id ))",
            "CREATE TABLE jdbcunittest.S_H2",
            "h2:mem:",
            "S_H2"),
        Arguments.of(
            "derby",
            new EmbeddedDriver().connect(jdbcUrls.get("derby"), null),
            "APP",
            "CREATE TABLE S_DERBY (id INTEGER not NULL, PRIMARY KEY ( id ))",
            "CREATE TABLE jdbcunittest.S_DERBY",
            "derby:memory:",
            "S_DERBY"),
        Arguments.of(
            "hsqldb",
            new JDBCDriver().connect(jdbcUrls.get("hsqldb"), null),
            "SA",
            "CREATE TABLE PUBLIC.S_HSQLDB (id INTEGER not NULL, PRIMARY KEY ( id ))",
            "CREATE TABLE PUBLIC.S_HSQLDB",
            "hsqldb:mem:",
            "PUBLIC.S_HSQLDB"),
        Arguments.of(
            "h2",
            cpDatasources.get("tomcat").get("h2").getConnection(),
            null,
            "CREATE TABLE S_H2_TOMCAT (id INTEGER not NULL, PRIMARY KEY ( id ))",
            "CREATE TABLE jdbcunittest.S_H2_TOMCAT",
            "h2:mem:",
            "S_H2_TOMCAT"),
        Arguments.of(
            "derby",
            cpDatasources.get("tomcat").get("derby").getConnection(),
            "APP",
            "CREATE TABLE S_DERBY_TOMCAT (id INTEGER not NULL, PRIMARY KEY ( id ))",
            "CREATE TABLE jdbcunittest.S_DERBY_TOMCAT",
            "derby:memory:",
            "S_DERBY_TOMCAT"),
        Arguments.of(
            "hsqldb",
            cpDatasources.get("tomcat").get("hsqldb").getConnection(),
            "SA",
            "CREATE TABLE PUBLIC.S_HSQLDB_TOMCAT (id INTEGER not NULL, PRIMARY KEY ( id ))",
            "CREATE TABLE PUBLIC.S_HSQLDB_TOMCAT",
            "hsqldb:mem:",
            "PUBLIC.S_HSQLDB_TOMCAT"),
        Arguments.of(
            "h2",
            cpDatasources.get("hikari").get("h2").getConnection(),
            null,
            "CREATE TABLE S_H2_HIKARI (id INTEGER not NULL, PRIMARY KEY ( id ))",
            "CREATE TABLE jdbcunittest.S_H2_HIKARI",
            "h2:mem:",
            "S_H2_HIKARI"),
        Arguments.of(
            "derby",
            cpDatasources.get("hikari").get("derby").getConnection(),
            "APP",
            "CREATE TABLE S_DERBY_HIKARI (id INTEGER not NULL, PRIMARY KEY ( id ))",
            "CREATE TABLE jdbcunittest.S_DERBY_HIKARI",
            "derby:memory:",
            "S_DERBY_HIKARI"),
        Arguments.of(
            "hsqldb",
            cpDatasources.get("hikari").get("hsqldb").getConnection(),
            "SA",
            "CREATE TABLE PUBLIC.S_HSQLDB_HIKARI (id INTEGER not NULL, PRIMARY KEY ( id ))",
            "CREATE TABLE PUBLIC.S_HSQLDB_HIKARI",
            "hsqldb:mem:",
            "PUBLIC.S_HSQLDB_HIKARI"),
        Arguments.of(
            "h2",
            cpDatasources.get("c3p0").get("h2").getConnection(),
            null,
            "CREATE TABLE S_H2_C3P0 (id INTEGER not NULL, PRIMARY KEY ( id ))",
            "CREATE TABLE jdbcunittest.S_H2_C3P0",
            "h2:mem:",
            "S_H2_C3P0"),
        Arguments.of(
            "derby",
            cpDatasources.get("c3p0").get("derby").getConnection(),
            "APP",
            "CREATE TABLE S_DERBY_C3P0 (id INTEGER not NULL, PRIMARY KEY ( id ))",
            "CREATE TABLE jdbcunittest.S_DERBY_C3P0",
            "derby:memory:",
            "S_DERBY_C3P0"),
        Arguments.of(
            "hsqldb",
            cpDatasources.get("c3p0").get("hsqldb").getConnection(),
            "SA",
            "CREATE TABLE PUBLIC.S_HSQLDB_C3P0 (id INTEGER not NULL, PRIMARY KEY ( id ))",
            "CREATE TABLE PUBLIC.S_HSQLDB_C3P0",
            "hsqldb:mem:",
            "PUBLIC.S_HSQLDB_C3P0"));
  }

  @ParameterizedTest
  @MethodSource("statementUpdateStream")
  @DisplayName(
      "statement update on #system with #connection.getClass().getCanonicalName() generates a span")
  void testStatementUpdate(
      String system,
      Connection connection,
      String username,
      String query,
      String spanName,
      String url,
      String table)
      throws SQLException {
    Statement statement = connection.createStatement();
    cleanup.deferCleanup(statement);
    cleanup.deferCleanup(connection);
    String sql = connection.nativeSQL(query);
    testing.runWithSpan("parent", () -> statement.execute(sql));

    assertThat(statement.getUpdateCount()).isEqualTo(0);

    testing.waitAndAssertTraces(
        trace ->
            trace.hasSpansSatisfyingExactly(
                span -> span.hasName("parent").hasKind(SpanKind.INTERNAL).hasNoParent(),
                span ->
                    span.hasName(spanName)
                        .hasKind(SpanKind.CLIENT)
                        .hasParent(trace.getSpan(0))
                        .hasAttributesSatisfying(
                            equalTo(DbIncubatingAttributes.DB_SYSTEM, system),
                            equalTo(DbIncubatingAttributes.DB_NAME, dbNameLower),
                            satisfies(
                                DbIncubatingAttributes.DB_USER,
                                val ->
                                    val.satisfiesAnyOf(
                                        v -> assertThat(v).isEqualTo(username),
                                        v -> assertThat(v).isNull())),
                            equalTo(getDbConnectionStringKey(), url),
                            equalTo(DbIncubatingAttributes.DB_STATEMENT, query),
                            equalTo(DbIncubatingAttributes.DB_OPERATION, "CREATE TABLE"),
                            equalTo(DbIncubatingAttributes.DB_SQL_TABLE, table))));
  }

  static Stream<Arguments> preparedStatementUpdateStream() throws SQLException {
    return Stream.of(
        Arguments.of(
            "h2",
            new org.h2.Driver().connect(jdbcUrls.get("h2"), null),
            null,
            "CREATE TABLE PS_H2 (id INTEGER not NULL, PRIMARY KEY ( id ))",
            "CREATE TABLE jdbcunittest.PS_H2",
            "h2:mem:",
            "PS_H2"),
        Arguments.of(
            "derby",
            new EmbeddedDriver().connect(jdbcUrls.get("derby"), null),
            "APP",
            "CREATE TABLE PS_DERBY (id INTEGER not NULL, PRIMARY KEY ( id ))",
            "CREATE TABLE jdbcunittest.PS_DERBY",
            "derby:memory:",
            "PS_DERBY"),
        Arguments.of(
            "h2",
            cpDatasources.get("tomcat").get("h2").getConnection(),
            null,
            "CREATE TABLE PS_H2_TOMCAT (id INTEGER not NULL, PRIMARY KEY ( id ))",
            "CREATE TABLE jdbcunittest.PS_H2_TOMCAT",
            "h2:mem:",
            "PS_H2_TOMCAT"),
        Arguments.of(
            "derby",
            cpDatasources.get("tomcat").get("derby").getConnection(),
            "APP",
            "CREATE TABLE PS_DERBY_TOMCAT (id INTEGER not NULL, PRIMARY KEY ( id ))",
            "CREATE TABLE jdbcunittest.PS_DERBY_TOMCAT",
            "derby:memory:",
            "PS_DERBY_TOMCAT"),
        Arguments.of(
            "h2",
            cpDatasources.get("hikari").get("h2").getConnection(),
            null,
            "CREATE TABLE PS_H2_HIKARI (id INTEGER not NULL, PRIMARY KEY ( id ))",
            "CREATE TABLE jdbcunittest.PS_H2_HIKARI",
            "h2:mem:",
            "PS_H2_HIKARI"),
        Arguments.of(
            "derby",
            cpDatasources.get("hikari").get("derby").getConnection(),
            "APP",
            "CREATE TABLE PS_DERBY_HIKARI (id INTEGER not NULL, PRIMARY KEY ( id ))",
            "CREATE TABLE jdbcunittest.PS_DERBY_HIKARI",
            "derby:memory:",
            "PS_DERBY_HIKARI"),
        Arguments.of(
            "h2",
            cpDatasources.get("c3p0").get("h2").getConnection(),
            null,
            "CREATE TABLE PS_H2_C3P0 (id INTEGER not NULL, PRIMARY KEY ( id ))",
            "CREATE TABLE jdbcunittest.PS_H2_C3P0",
            "h2:mem:",
            "PS_H2_C3P0"),
        Arguments.of(
            "derby",
            cpDatasources.get("c3p0").get("derby").getConnection(),
            "APP",
            "CREATE TABLE PS_DERBY_C3P0 (id INTEGER not NULL, PRIMARY KEY ( id ))",
            "CREATE TABLE jdbcunittest.PS_DERBY_C3P0",
            "derby:memory:",
            "PS_DERBY_C3P0"));
  }

  @ParameterizedTest
  @MethodSource("preparedStatementUpdateStream")
  @DisplayName(
      "prepared statement update on #system with #connection.getClass().getCanonicalName() generates a span")
  void testPreparedStatementUpdate(
      String system,
      Connection connection,
      String username,
      String query,
      String spanName,
      String url,
      String table)
      throws SQLException {
    String sql = connection.nativeSQL(query);
    PreparedStatement statement = connection.prepareStatement(sql);
    cleanup.deferCleanup(statement);
    cleanup.deferCleanup(connection);
    testing.runWithSpan("parent", () -> statement.executeUpdate() == 0);

    testing.waitAndAssertTraces(
        trace ->
            trace.hasSpansSatisfyingExactly(
                span -> span.hasName("parent").hasKind(SpanKind.INTERNAL).hasNoParent(),
                span ->
                    span.hasName(spanName)
                        .hasKind(SpanKind.CLIENT)
                        .hasParent(trace.getSpan(0))
                        .hasAttributesSatisfying(
                            equalTo(DbIncubatingAttributes.DB_SYSTEM, system),
                            equalTo(DbIncubatingAttributes.DB_NAME, dbNameLower),
                            satisfies(
                                DbIncubatingAttributes.DB_USER,
                                val ->
                                    val.satisfiesAnyOf(
                                        v -> assertThat(v).isEqualTo(username),
                                        v -> assertThat(v).isNull())),
                            equalTo(getDbConnectionStringKey(), url),
                            equalTo(DbIncubatingAttributes.DB_STATEMENT, query),
                            equalTo(DbIncubatingAttributes.DB_OPERATION, "CREATE TABLE"),
                            equalTo(DbIncubatingAttributes.DB_SQL_TABLE, table))));
  }

  static Stream<Arguments> connectionConstructorStream() {
    return Stream.of(
        Arguments.of(
            true,
            "h2",
            new org.h2.Driver(),
            "jdbc:h2:mem:" + dbName,
            null,
            "SELECT 3;",
            "SELECT ?;",
            "SELECT " + dbNameLower,
            "h2:mem:",
            null),
        Arguments.of(
            true,
            "derby",
            new EmbeddedDriver(),
            "jdbc:derby:memory:" + dbName + ";create=true",
            "APP",
            "SELECT 3 FROM SYSIBM.SYSDUMMY1",
            "SELECT ? FROM SYSIBM.SYSDUMMY1",
            "SELECT SYSIBM.SYSDUMMY1",
            "derby:memory:",
            "SYSIBM.SYSDUMMY1"),
        Arguments.of(
            false,
            "h2",
            new org.h2.Driver(),
            "jdbc:h2:mem:" + dbName,
            null,
            "SELECT 3;",
            "SELECT ?;",
            "SELECT " + dbNameLower,
            "h2:mem:",
            null),
        Arguments.of(
            false,
            "derby",
            new EmbeddedDriver(),
            "jdbc:derby:memory:" + dbName + ";create=true",
            "APP",
            "SELECT 3 FROM SYSIBM.SYSDUMMY1",
            "SELECT ? FROM SYSIBM.SYSDUMMY1",
            "SELECT SYSIBM.SYSDUMMY1",
            "derby:memory:",
            "SYSIBM.SYSDUMMY1"));
  }

  @SuppressWarnings("CatchingUnchecked")
  @ParameterizedTest
  @MethodSource("connectionConstructorStream")
  @DisplayName(
      "connection constructor throwing then generating correct spans after recovery using #driver connection (prepare statement = #prepareStatement)")
  void testConnectionConstructorThrowing(
      boolean prepareStatement,
      String system,
      Driver driver,
      String jdbcUrl,
      String username,
      String query,
      String sanitizedQuery,
      String spanName,
      String url,
      String table)
      throws SQLException {
    Connection connection = null;

    try {
      connection = new TestConnection(true);
    } catch (Exception ignored) {
      connection = driver.connect(jdbcUrl, null);
    }
    cleanup.deferCleanup(connection);
    Connection finalConnection = connection;
    ResultSet rs =
        testing.runWithSpan(
            "parent",
            () -> {
              if (prepareStatement) {
                PreparedStatement stmt = finalConnection.prepareStatement(query);
                cleanup.deferCleanup(stmt);
                return stmt.executeQuery();
              } else {
                Statement stmt = finalConnection.createStatement();
                cleanup.deferCleanup(stmt);
                return stmt.executeQuery(query);
              }
            });

    rs.next();
    assertThat(rs.getInt(1)).isEqualTo(3);
    testing.waitAndAssertTraces(
        trace ->
            trace.hasSpansSatisfyingExactly(
                span -> span.hasName("parent").hasKind(SpanKind.INTERNAL).hasNoParent(),
                span ->
                    span.hasName(spanName)
                        .hasKind(SpanKind.CLIENT)
                        .hasParent(trace.getSpan(0))
                        .hasAttributesSatisfying(
                            equalTo(DbIncubatingAttributes.DB_SYSTEM, system),
                            equalTo(DbIncubatingAttributes.DB_NAME, dbNameLower),
                            satisfies(
                                DbIncubatingAttributes.DB_USER,
                                val ->
                                    val.satisfiesAnyOf(
                                        v -> assertThat(v).isEqualTo(username),
                                        v -> assertThat(v).isNull())),
                            equalTo(getDbConnectionStringKey(), url),
                            equalTo(DbIncubatingAttributes.DB_STATEMENT, sanitizedQuery),
                            equalTo(DbIncubatingAttributes.DB_OPERATION, "SELECT"),
                            equalTo(DbIncubatingAttributes.DB_SQL_TABLE, table))));
  }

  static Stream<Arguments> getConnectionStream() {
    return Stream.of(
        Arguments.of(
            new JdbcDataSource(),
            (Consumer<DataSource>) ds -> ((JdbcDataSource) ds).setUrl(jdbcUrls.get("h2")),
            "h2",
            null,
            "h2:mem:"),
        Arguments.of(
            new EmbeddedDataSource(),
            (Consumer<DataSource>)
                ds -> ((EmbeddedDataSource) ds).setDatabaseName("memory:" + dbName),
            "derby",
            "APP",
            "derby:memory:"),
        Arguments.of(cpDatasources.get("hikari").get("h2"), null, "h2", null, "h2:mem:"),
        Arguments.of(
            cpDatasources.get("hikari").get("derby"), null, "derby", "APP", "derby:memory:"),
        Arguments.of(cpDatasources.get("c3p0").get("h2"), null, "h2", null, "h2:mem:"),
        Arguments.of(
            cpDatasources.get("c3p0").get("derby"), null, "derby", "APP", "derby:memory:"));
  }

  @ParameterizedTest(autoCloseArguments = false)
  @MethodSource("getConnectionStream")
  @DisplayName(
      "calling #datasource.class.simpleName getConnection generates a span when under existing trace")
  void testGetConnection(
      DataSource datasource,
      Consumer<DataSource> init,
      String system,
      String user,
      String connectionString)
      throws SQLException {
    // Tomcat's pool doesn't work because the getConnection method is
    // implemented in a parent class that doesn't implement DataSource
    boolean recursive = datasource instanceof EmbeddedDataSource;

    if (init != null) {
      init.accept(datasource);
    }
    datasource.getConnection().close();

    testing.clearData();

    testing.runWithSpan(
        "parent",
        () -> {
          datasource.getConnection().close();
        });
    if (recursive) {
      testing.waitAndAssertTraces(
          trace ->
              trace.hasSpansSatisfyingExactly(
                  span -> span.hasName("parent").hasKind(SpanKind.INTERNAL).hasNoParent(),
                  span ->
                      span.hasName(datasource.getClass().getSimpleName() + ".getConnection")
                          .hasKind(SpanKind.INTERNAL)
                          .hasParent(trace.getSpan(0))
                          .hasAttributesSatisfying(
                              equalTo(
                                  CodeIncubatingAttributes.CODE_NAMESPACE,
                                  datasource.getClass().getName()),
                              equalTo(CodeIncubatingAttributes.CODE_FUNCTION, "getConnection"),
                              equalTo(DbIncubatingAttributes.DB_SYSTEM, system),
                              satisfies(
                                  DbIncubatingAttributes.DB_USER,
                                  val ->
                                      val.satisfiesAnyOf(
                                          v -> assertThat(v).isEqualTo(user),
                                          v -> assertThat(v).isNull())),
                              equalTo(DbIncubatingAttributes.DB_NAME, "jdbcunittest"),
                              equalTo(getDbConnectionStringKey(), connectionString)),
                  span ->
                      span.hasName(datasource.getClass().getSimpleName() + ".getConnection")
                          .hasKind(SpanKind.INTERNAL)
                          .hasParent(trace.getSpan(1))
                          .hasAttributesSatisfying(
                              equalTo(
                                  CodeIncubatingAttributes.CODE_NAMESPACE,
                                  datasource.getClass().getName()),
                              equalTo(CodeIncubatingAttributes.CODE_FUNCTION, "getConnection"),
                              equalTo(DbIncubatingAttributes.DB_SYSTEM, system),
                              satisfies(
                                  DbIncubatingAttributes.DB_USER,
                                  val ->
                                      val.satisfiesAnyOf(
                                          v -> assertThat(v).isEqualTo(user),
                                          v -> assertThat(v).isNull())),
                              equalTo(DbIncubatingAttributes.DB_NAME, "jdbcunittest"),
                              equalTo(getDbConnectionStringKey(), connectionString))));
    } else {
      testing.waitAndAssertTraces(
          trace ->
              trace.hasSpansSatisfyingExactly(
                  span -> span.hasName("parent").hasKind(SpanKind.INTERNAL).hasNoParent(),
                  span ->
                      span.hasName(datasource.getClass().getSimpleName() + ".getConnection")
                          .hasKind(SpanKind.INTERNAL)
                          .hasParent(trace.getSpan(0))
                          .hasAttributesSatisfying(
                              equalTo(
                                  CodeIncubatingAttributes.CODE_NAMESPACE,
                                  datasource.getClass().getName()),
                              equalTo(CodeIncubatingAttributes.CODE_FUNCTION, "getConnection"),
                              equalTo(DbIncubatingAttributes.DB_SYSTEM, system),
                              satisfies(
                                  DbIncubatingAttributes.DB_USER,
                                  val ->
                                      val.satisfiesAnyOf(
                                          v -> assertThat(v).isEqualTo(user),
                                          v -> assertThat(v).isNull())),
                              equalTo(DbIncubatingAttributes.DB_NAME, "jdbcunittest"),
                              equalTo(getDbConnectionStringKey(), connectionString))));
    }
  }

  @SuppressWarnings("deprecation") // TODO DbIncubatingAttributes.DB_CONNECTION_STRING deprecation
  AttributeKey<String> getDbConnectionStringKey() {
    return DbIncubatingAttributes.DB_CONNECTION_STRING;
  }

  @ParameterizedTest
  @DisplayName("test getClientInfo exception")
  @ValueSource(strings = "testing 123")
  void testGetClientInfoException(String query) throws SQLException {
    TestConnection connection = new TestConnection(false);
    cleanup.deferCleanup(connection);
    connection.setUrl("jdbc:testdb://localhost");

    Statement statement =
        testing.runWithSpan(
            "parent",
            () -> {
              Statement stmt = connection.createStatement();
              stmt.executeQuery(query);
              return stmt;
            });
    cleanup.deferCleanup(statement);

    testing.waitAndAssertTraces(
        trace ->
            trace.hasSpansSatisfyingExactly(
                span -> span.hasName("parent").hasKind(SpanKind.INTERNAL).hasNoParent(),
                span ->
                    span.hasName("DB Query")
                        .hasKind(SpanKind.CLIENT)
                        .hasParent(trace.getSpan(0))
                        .hasAttributesSatisfying(
                            equalTo(DbIncubatingAttributes.DB_SYSTEM, "other_sql"),
                            equalTo(DbIncubatingAttributes.DB_STATEMENT, "testing ?"),
                            equalTo(getDbConnectionStringKey(), "testdb://localhost"),
                            equalTo(ServerAttributes.SERVER_ADDRESS, "localhost"))));
  }

  static Stream<Arguments> spanNameStream() {
    return Stream.of(
        Arguments.of(
            "jdbc:testdb://localhost?databaseName=test",
            "SELECT * FROM table",
            "SELECT * FROM table",
            "SELECT test.table",
            "test",
            "SELECT",
            "table"),
        Arguments.of(
            "jdbc:testdb://localhost?databaseName=test",
            "SELECT 42",
            "SELECT ?",
            "SELECT test",
            "test",
            "SELECT",
            null),
        Arguments.of(
            "jdbc:testdb://localhost",
            "SELECT * FROM table",
            "SELECT * FROM table",
            "SELECT table",
            null,
            "SELECT",
            "table"),
        Arguments.of(
            "jdbc:testdb://localhost?databaseName=test",
            "CREATE TABLE table",
            "CREATE TABLE table",
            "CREATE TABLE test.table",
            "test",
            "CREATE TABLE",
            "table"),
        Arguments.of(
            "jdbc:testdb://localhost",
            "CREATE TABLE table",
            "CREATE TABLE table",
            "CREATE TABLE table",
            null,
            "CREATE TABLE",
            "table"));
  }

  @ParameterizedTest
  @DisplayName("should produce proper span name #spanName")
  @MethodSource("spanNameStream")
  void testProduceProperSpanName(
      String url,
      String query,
      String sanitizedQuery,
      String spanName,
      String databaseName,
      String operation,
      String table)
      throws SQLException {
    Driver driver = new TestDriver();
    Connection connection = driver.connect(url, null);
    cleanup.deferCleanup(connection);

    testing.runWithSpan(
        "parent",
        () -> {
          Statement statement = connection.createStatement();
          statement.executeQuery(query);
        });

    testing.waitAndAssertTraces(
        trace ->
            trace.hasSpansSatisfyingExactly(
                span -> span.hasName("parent").hasKind(SpanKind.INTERNAL).hasNoParent(),
                span ->
                    span.hasName(spanName)
                        .hasKind(SpanKind.CLIENT)
                        .hasParent(trace.getSpan(0))
                        .hasAttributesSatisfying(
                            equalTo(DbIncubatingAttributes.DB_SYSTEM, "other_sql"),
                            equalTo(DbIncubatingAttributes.DB_NAME, databaseName),
                            equalTo(getDbConnectionStringKey(), "testdb://localhost"),
                            equalTo(DbIncubatingAttributes.DB_STATEMENT, sanitizedQuery),
                            equalTo(DbIncubatingAttributes.DB_OPERATION, operation),
                            equalTo(DbIncubatingAttributes.DB_SQL_TABLE, table),
                            equalTo(ServerAttributes.SERVER_ADDRESS, "localhost"))));
  }

  @ParameterizedTest
  @ValueSource(strings = {"hikari", "tomcat", "c3p0"})
  @DisplayName("#connectionPoolName connections should be cached in case of wrapped connections")
  void testConnectionCached(String connectionPoolName) throws SQLException {
    String dbType = "hsqldb";
    DataSource ds = createDs(connectionPoolName, dbType, jdbcUrls.get(dbType));
    cleanup.deferCleanup(
        () -> {
          if (ds instanceof Closeable) {
            ((Closeable) ds).close();
          }
        });
    String query = "SELECT 3 FROM INFORMATION_SCHEMA.SYSTEM_USERS";
    int numQueries = 5;
    int[] res = new int[numQueries];

    for (int i = 0; i < numQueries; ++i) {
      try (Connection connection = ds.getConnection();
          PreparedStatement statement = connection.prepareStatement(query)) {
        ResultSet rs = statement.executeQuery();
        if (rs.next()) {
          res[i] = rs.getInt(1);
        } else {
          res[i] = 0;
        }
      }
    }

    for (int i = 0; i < numQueries; ++i) {
      assertThat(res[i]).isEqualTo(3);
    }

    List<Consumer<TraceAssert>> assertions = new ArrayList<>();
    Consumer<TraceAssert> traceAssertConsumer =
        trace ->
            trace.hasSpansSatisfyingExactly(
                span ->
                    span.hasName("SELECT INFORMATION_SCHEMA.SYSTEM_USERS")
                        .hasKind(SpanKind.CLIENT)
                        .hasAttributesSatisfying(
                            equalTo(DbIncubatingAttributes.DB_SYSTEM, "hsqldb"),
                            equalTo(DbIncubatingAttributes.DB_NAME, dbNameLower),
                            equalTo(DbIncubatingAttributes.DB_USER, "SA"),
                            equalTo(getDbConnectionStringKey(), "hsqldb:mem:"),
                            equalTo(
                                DbIncubatingAttributes.DB_STATEMENT,
                                "SELECT ? FROM INFORMATION_SCHEMA.SYSTEM_USERS"),
                            equalTo(DbIncubatingAttributes.DB_OPERATION, "SELECT"),
                            equalTo(
                                DbIncubatingAttributes.DB_SQL_TABLE,
                                "INFORMATION_SCHEMA.SYSTEM_USERS")));
    for (int i = 0; i < numQueries; i++) {
      assertions.add(traceAssertConsumer);
    }

    testing.waitAndAssertTraces(assertions);
  }

  @FunctionalInterface
  public interface ThrowingBiConsumer<T, U> {
    void accept(T t, U u) throws Exception;
  }

  static Stream<Arguments> recursiveStatementsStream() {
    return Stream.of(
        Arguments.of(
            "getMetaData() uses Statement, test Statement",
            false,
            (ThrowingBiConsumer<Connection, String>)
                (con, query) -> con.createStatement().executeQuery(query)),
        Arguments.of(
            "getMetaData() uses PreparedStatement, test Statement",
            true,
            (ThrowingBiConsumer<Connection, String>)
                (con, query) -> con.createStatement().executeQuery(query)),
        Arguments.of(
            "getMetaData() uses Statement, test PreparedStatement",
            false,
            (ThrowingBiConsumer<Connection, String>)
                (con, query) -> con.prepareStatement(query).executeQuery()),
        Arguments.of(
            "getMetaData() uses PreparedStatement, test PreparedStatement",
            true,
            (ThrowingBiConsumer<Connection, String>)
                (con, query) -> con.prepareStatement(query).executeQuery()));
  }

  // regression test for
  // https://github.com/open-telemetry/opentelemetry-java-instrumentation/issues/2644
  @ParameterizedTest
  @DisplayName("should handle recursive Statements inside Connection.getMetaData(): #desc")
  @MethodSource("recursiveStatementsStream")
  void testHandleRecursiveStatements(
      String desc,
      boolean usePreparedStatementInConnection,
      ThrowingBiConsumer<Connection, String> executeQueryFunction)
      throws Exception {
    DbCallingConnection connection = new DbCallingConnection(usePreparedStatementInConnection);
    connection.setUrl("jdbc:testdb://localhost");

    testing.runWithSpan(
        "parent",
        () -> {
          executeQueryFunction.accept(connection, "SELECT * FROM table");
        });

    testing.waitAndAssertTraces(
        trace ->
            trace.hasSpansSatisfyingExactly(
                span -> span.hasName("parent").hasKind(SpanKind.INTERNAL).hasNoParent(),
                span ->
                    span.hasName("SELECT table")
                        .hasKind(SpanKind.CLIENT)
                        .hasParent(trace.getSpan(0))
                        .hasAttributesSatisfying(
                            equalTo(DbIncubatingAttributes.DB_SYSTEM, "other_sql"),
                            equalTo(getDbConnectionStringKey(), "testdb://localhost"),
                            equalTo(DbIncubatingAttributes.DB_STATEMENT, "SELECT * FROM table"),
                            equalTo(DbIncubatingAttributes.DB_OPERATION, "SELECT"),
                            equalTo(DbIncubatingAttributes.DB_SQL_TABLE, "table"),
                            equalTo(ServerAttributes.SERVER_ADDRESS, "localhost"))));
  }

  static class DbCallingConnection extends TestConnection {
    final boolean usePreparedStatement;

    DbCallingConnection(boolean usePreparedStatement) {
      super(false);
      this.usePreparedStatement = usePreparedStatement;
    }

    @Override
    public DatabaseMetaData getMetaData() throws SQLException {
      // simulate retrieving DB metadata from the DB itself
      if (usePreparedStatement) {
        prepareStatement("SELECT * from DB_METADATA").executeQuery();
      } else {
        createStatement().executeQuery("SELECT * from DB_METADATA");
      }
      return super.getMetaData();
    }
  }

  // regression test for
  // https://github.com/open-telemetry/opentelemetry-java-instrumentation/issues/6015
  @DisplayName("test proxy statement")
  @Test
  void testProxyStatement() throws Exception {
    Connection connection = new org.h2.Driver().connect(jdbcUrls.get("h2"), null);
    Statement statement = connection.createStatement();
    cleanup.deferCleanup(statement);
    cleanup.deferCleanup(connection);

    Statement proxyStatement = ProxyStatementFactory.proxyStatement(statement);
    ResultSet resultSet =
        testing.runWithSpan("parent", () -> proxyStatement.executeQuery("SELECT 3"));

    resultSet.next();
    assertThat(resultSet.getInt(1)).isEqualTo(3);
    testing.waitAndAssertTraces(
        trace ->
            trace.hasSpansSatisfyingExactly(
                span -> span.hasName("parent").hasKind(SpanKind.INTERNAL).hasNoParent(),
                span ->
                    span.hasName("SELECT " + dbNameLower)
                        .hasKind(SpanKind.CLIENT)
                        .hasParent(trace.getSpan(0))));
  }

  // regression test for
  // https://github.com/open-telemetry/opentelemetry-java-instrumentation/issues/9359
  @DisplayName("test proxy prepared statement")
  @Test
  void testProxyPreparedStatement() throws SQLException {
    Connection connection = new org.h2.Driver().connect(jdbcUrls.get("h2"), null);
    PreparedStatement statement = connection.prepareStatement("SELECT 3");
    cleanup.deferCleanup(statement);
    cleanup.deferCleanup(connection);

    PreparedStatement proxyStatement = ProxyStatementFactory.proxyPreparedStatement(statement);
    ResultSet resultSet = testing.runWithSpan("parent", () -> proxyStatement.executeQuery());

    resultSet.next();
    assertThat(resultSet.getInt(1)).isEqualTo(3);
    testing.waitAndAssertTraces(
        trace ->
            trace.hasSpansSatisfyingExactly(
                span -> span.hasName("parent").hasKind(SpanKind.INTERNAL).hasNoParent(),
                span ->
                    span.hasName("SELECT " + dbNameLower)
                        .hasKind(SpanKind.CLIENT)
                        .hasParent(trace.getSpan(0))));
  }
}
