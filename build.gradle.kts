import com.google.protobuf.gradle.id

plugins {
    `java-library`
    id("com.google.protobuf") version "0.10.0"
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
}

tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to project.name,
            "Implementation-Version" to project.version
        )
    }
}
