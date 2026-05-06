import com.google.protobuf.gradle.id
import com.vanniktech.maven.publish.JavaLibrary
import com.vanniktech.maven.publish.JavadocJar

plugins {
    `java-library`
    id("com.google.protobuf") version "0.10.0"
    id("com.vanniktech.maven.publish") version "0.33.0"
}

group = "io.github.zhh2001"
version = "0.1.0-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<JavaCompile> {
    options.release.set(21)
}

repositories {
    mavenCentral()
}

val grpcVersion = "1.80.0"
val protobufVersion = "4.34.1"
val slf4jVersion = "2.0.16"

dependencies {
    api("io.grpc:grpc-stub:$grpcVersion")
    api("io.grpc:grpc-protobuf:$grpcVersion")
    api("com.google.protobuf:protobuf-java:$protobufVersion")

    implementation("io.grpc:grpc-netty-shaded:$grpcVersion")
    implementation("org.slf4j:slf4j-api:$slf4jVersion")

    // proto-google-common-protos provides google.rpc.Status (used by p4runtime.proto)
    // as PRE-COMPILED Java classes. Its .proto files are also auto-extracted onto
    // protoc's include path via the compileClasspath. We deliberately do NOT add it
    // to the `protobuf` configuration: that would re-compile google/protobuf/*.proto
    // (descriptor.proto, any.proto, ...) and the resulting classes would shadow the
    // versions in protobuf-java itself, leading to NoSuchMethodError/NoSuchFieldError
    // at runtime.
    implementation("com.google.api.grpc:proto-google-common-protos:2.63.2")

    compileOnly("org.apache.tomcat:annotations-api:6.0.53")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.testcontainers:testcontainers:2.0.5")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter:2.0.5")
    testImplementation("io.grpc:grpc-inprocess:$grpcVersion")
    testImplementation("ch.qos.logback:logback-classic:1.5.15")
    testImplementation("org.awaitility:awaitility:4.2.2")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:$protobufVersion"
    }
    plugins {
        id("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:$grpcVersion"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                id("grpc")
            }
        }
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
    // grpc-netty-shaded touches sun.misc.Unsafe and System.loadLibrary; on JDK 24+
    // the JVM warns or (eventually) refuses without an explicit native-access grant.
    // See NOTES.md for the classification record.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to project.name,
            "Implementation-Version" to project.version
        )
    }
}

// ---------------- Maven Central publishing -----------------------------------
//
// Publishing to Maven Central (Central Portal, post-OSSRH-EOL) is configured here
// via the vanniktech plugin. The plugin generates a sources-jar and a javadoc-jar
// in addition to the main jar, populates the POM with the metadata Maven Central
// requires (name / description / url / licenses / scm / developers), and uploads
// to the new https://central.sonatype.com/api/v1/publisher/upload endpoint.
//
// Local dry-run (no GPG, no upload):
//     ./gradlew publishToMavenLocal
// Real release (requires GPG + Central Portal credentials in ~/.gradle/gradle.properties):
//     ./gradlew publishToMavenCentral
// See RELEASING.md for the full release procedure.

mavenPublishing {
    coordinates(group.toString(), "jp4", version.toString())

    pom {
        name.set("jp4")
        description.set(
            "A native Java client library for P4Runtime — connect to a " +
            "P4Runtime-enabled device, push pipelines, read and write table " +
            "entries, and send and receive packets through the StreamChannel."
        )
        inceptionYear.set("2026")
        url.set("https://github.com/zhh2001/jp4/")

        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("zhh2001")
                name.set("zhh2001")
                email.set("zhh2001@users.noreply.github.com")
                url.set("https://github.com/zhh2001/")
            }
        }
        scm {
            url.set("https://github.com/zhh2001/jp4/")
            connection.set("scm:git:git://github.com/zhh2001/jp4.git")
            developerConnection.set("scm:git:ssh://git@github.com/zhh2001/jp4.git")
        }
    }

    // Configure as a plain Java library; ship sources + javadoc jars (Maven Central
    // requires both for OSS releases). JavadocJar.Javadoc() invokes the standard
    // javadoc tool; switch to JavadocJar.Empty() if javadoc generation breaks the
    // build for an unrelated reason and we need a temporary workaround.
    configure(JavaLibrary(JavadocJar.Javadoc(), sourcesJar = true))

    // Central Portal target. automaticRelease = false means uploads land in
    // staging and require manual click-through in the Portal UI before
    // becoming public on Maven Central — appropriate while the release ritual
    // is still being learned. v0.1.x patches can flip this to true once the
    // pipeline is well-trodden.
    publishToMavenCentral(automaticRelease = false)

    // Sign artifacts only when GPG keys are configured (per-user
    // ~/.gradle/gradle.properties or CI secrets, never in the repo).
    // publishToMavenLocal works without keys because of this guard;
    // publishToMavenCentral fails without them, which is the right behaviour —
    // signed artifacts are a Central requirement.
    if (project.hasProperty("signingInMemoryKey")) {
        signAllPublications()
    }
}

// Java 21's javadoc tool is strict about missing tags and a couple of warnings
// the older toolchain ignored. We let it surface real issues but downgrade the
// "missing -Xdoclint" classes that don't affect doc quality so a single missing
// @return doesn't gate a release. Keep this list short — adding to it should be
// preceded by fixing the underlying issue if possible.
tasks.withType<Javadoc> {
    (options as StandardJavadocDocletOptions).apply {
        addStringOption("Xdoclint:none", "-quiet")
        // Register @implNote so it renders properly. javadoc-tool warns
        // "unknown tag" otherwise, even though @implNote is a standard JEP-8068562 tag.
        tags("implNote:a:Implementation Note:")
    }
}
