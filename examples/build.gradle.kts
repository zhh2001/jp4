/*
 * Common settings for all example modules. Each module overrides mainClass
 * in its own build.gradle.kts.
 */
subprojects {
    apply(plugin = "application")
    apply(plugin = "java")

    extensions.configure<JavaPluginExtension> {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    tasks.withType<JavaCompile>().configureEach {
        options.release.set(21)
    }

    repositories {
        mavenCentral()
    }

    dependencies {
        // jp4 main artifact only — no test fixtures, no Awaitility, no Testcontainers.
        // Composite build (settings.gradle.kts includeBuild("..")) substitutes this
        // coordinate with the in-tree jp4 main module.
        "implementation"("io.github.zhh2001:jp4:0.1.0-SNAPSHOT")
        // SLF4J runtime so PacketIn drop warnings and similar log lines are visible.
        "runtimeOnly"("org.slf4j:slf4j-simple:2.0.16")
    }

    // gRPC + Netty want native access on JDK 24+; same flag the main test JVM uses.
    tasks.withType<JavaExec>().configureEach {
        jvmArgs("--enable-native-access=ALL-UNNAMED")
    }
}
