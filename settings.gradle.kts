pluginManagement {
  plugins {
    id("com.github.jk1.dependency-license-report") version "2.8"
    id("com.google.cloud.tools.jib") version "3.4.3"
    id("com.gradle.plugin-publish") version "1.2.1"
    id("io.github.gradle-nexus.publish-plugin") version "2.0.0"
    id("org.jetbrains.kotlin.jvm") version "2.0.0"
    id("org.xbib.gradle.plugin.jflex") version "3.0.2"
    id("org.unbroken-dome.xjc") version "2.0.0"
    id("org.graalvm.buildtools.native") version "0.10.2"
  }
}

plugins {
  id("com.gradle.develocity") version "3.17.6"
  id("com.gradle.common-custom-user-data-gradle-plugin") version "2.0.2"
  id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
  // this can't live in pluginManagement currently due to
  // https://github.com/bmuschko/gradle-docker-plugin/issues/1123
  // in particular, these commands are failing (reproducible locally):
  // ./gradlew :smoke-tests:images:servlet:buildLinuxTestImages pushMatrix -PsmokeTestServer=jetty
  // ./gradlew :smoke-tests:images:servlet:buildWindowsTestImages pushMatrix -PsmokeTestServer=jetty
  id("com.bmuschko.docker-remote-api") version "9.4.0" apply false
}

dependencyResolutionManagement {
  repositories {
    mavenCentral()
    mavenLocal()
  }

  versionCatalogs {
    fun addSpringBootCatalog(name: String, minVersion: String, maxVersion: String) {
      val latestDepTest = gradle.startParameter.projectProperties["testLatestDeps"] == "true"
      create(name) {
        val version =
          gradle.startParameter.projectProperties["${name}Version"]
            ?: (if (latestDepTest) maxVersion else minVersion)
        plugin("versions", "org.springframework.boot").version(version)
      }
    }
    // r2dbc is not compatible with earlier versions
    addSpringBootCatalog("springBoot2", "2.6.15", "2.+")
    // spring boot 3.0 is not compatible with graalvm native image
    addSpringBootCatalog("springBoot31", "3.1.0", "3.+")
    addSpringBootCatalog("springBoot32", "3.2.0", "3.+")
  }
}

val gradleEnterpriseServer = "https://ge.opentelemetry.io"
val isCI = System.getenv("CI") != null
val geAccessKey = System.getenv("GRADLE_ENTERPRISE_ACCESS_KEY") ?: ""

// if GE access key is not given and we are in CI, then we publish to scans.gradle.com
val useScansGradleCom = isCI && geAccessKey.isEmpty()

if (useScansGradleCom) {
  develocity {
    buildScan {
      termsOfUseUrl = "https://gradle.com/help/legal-terms-of-use"
      termsOfUseAgree = "yes"
      uploadInBackground = !isCI

      capture {
        fileFingerprints = true
      }

      buildScanPublished {
        File("build-scan.txt").printWriter().use { writer ->
          writer.println(buildScanUri)
        }
      }
    }
  }
} else {
  develocity {
    server = gradleEnterpriseServer
    buildScan {
      uploadInBackground = !isCI
      publishing.onlyIf { it.isAuthenticated }

      capture {
        fileFingerprints = true
      }

      gradle.startParameter.projectProperties["testJavaVersion"]?.let { tag(it) }
      gradle.startParameter.projectProperties["testJavaVM"]?.let { tag(it) }
      gradle.startParameter.projectProperties["smokeTestSuite"]?.let {
        value("Smoke test suite", it)
      }

      buildScanPublished {
        File("build-scan.txt").printWriter().use { writer ->
          writer.println(buildScanUri)
        }
      }
    }
  }

  buildCache {
    remote(develocity.buildCache) {
      isPush = isCI && geAccessKey.isNotEmpty()
    }
  }
}

rootProject.name = "opentelemetry-java-instrumentation"

includeBuild("conventions")

include(":custom-checks")

include(":muzzle")

// agent projects
include(":opentelemetry-api-shaded-for-instrumenting")
include(":opentelemetry-instrumentation-annotations-shaded-for-instrumenting")
include(":opentelemetry-instrumentation-api-shaded-for-instrumenting")
include(":javaagent-bootstrap")
include(":javaagent-extension-api")
include(":javaagent-tooling")
include(":javaagent-tooling:javaagent-tooling-java9")
include(":javaagent-internal-logging-application")
include(":javaagent-internal-logging-simple")
include(":javaagent")
include(":sdk-autoconfigure-support")

include(":instrumentation-api")
include(":instrumentation-api-incubator")
include(":instrumentation-annotations")
include(":instrumentation-annotations-support")

// misc
include(":dependencyManagement")
include(":testing:agent-exporter")
include(":testing:agent-for-testing")
include(":testing:armeria-shaded-for-testing")
include(":testing-common")

// smoke tests


include(":instrumentation:apache-dubbo-2.7:javaagent")
include(":instrumentation:apache-dubbo-2.7:library-autoconfigure")
include(":instrumentation:apache-dubbo-2.7:testing")
include(":instrumentation:executors:bootstrap")
include(":instrumentation:executors:javaagent")
include(":instrumentation:executors:testing")
include(":instrumentation:external-annotations:javaagent")
include(":instrumentation:internal:internal-application-logger:bootstrap")
include(":instrumentation:internal:internal-application-logger:javaagent")
include(":instrumentation:internal:internal-class-loader:javaagent")
include(":instrumentation:internal:internal-eclipse-osgi-3.6:javaagent")
include(":instrumentation:internal:internal-lambda:javaagent")
include(":instrumentation:internal:internal-lambda-java9:javaagent")
include(":instrumentation:internal:internal-reflection:javaagent")
include(":instrumentation:internal:internal-url-class-loader:javaagent")
include(":instrumentation:opentelemetry-api:opentelemetry-api-1.0:javaagent")
include(":instrumentation:opentelemetry-api:opentelemetry-api-1.4:javaagent")
include(":instrumentation:opentelemetry-instrumentation-annotations-1.16:javaagent")
include(":instrumentation:opentelemetry-instrumentation-api:javaagent")
include(":instrumentation:opentelemetry-instrumentation-api:testing")

// benchmark
