package io.github.zhh2001.jp4;

import com.google.rpc.Status;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test guarding against classpath shadowing of {@code google.protobuf.*}
 * runtime classes by locally regenerated protobuf descriptors.
 *
 * <p><b>What this prevents.</b> If {@code com.google.api.grpc:proto-google-common-protos}
 * (or any other artifact bundling {@code google/protobuf/*.proto}) is added to the
 * {@code protobuf} configuration in {@code build.gradle.kts}, the protobuf-gradle-plugin
 * will recompile {@code google/protobuf/descriptor.proto}, {@code any.proto}, etc. with
 * the project's protoc. The resulting {@code DescriptorProtos.class} files end up in the
 * main source set output and shadow the canonical versions inside the {@code protobuf-java}
 * jar at runtime.
 *
 * <p><b>Symptoms when this happens.</b> Code that triggers descriptor / editions
 * initialization (e.g. parsing a P4Info text-format file) fails with
 * {@code NoSuchMethodError} on {@code Descriptors.getEditionDefaults}, or
 * {@code NoSuchFieldError} on enum constants such as {@code EDITION_UNSTABLE}, because
 * the shadowed classes came from an older protoc that did not emit these symbols.
 *
 * <p><b>Correct configuration.</b> Common-protos belong on {@code implementation} only.
 * Their precompiled Java classes ship inside the jar; their {@code .proto} files are
 * automatically extracted onto protoc's include path via {@code compileClasspath}, so
 * imports like {@code import "google/rpc/status.proto"} resolve without us having to
 * regenerate them.
 */
class CommonProtosResolutionTest {

    @Test
    void googleRpcStatusBuilds() {
        Status s = Status.newBuilder()
                .setCode(0)
                .setMessage("ok")
                .build();
        assertEquals(0, s.getCode());
        assertEquals("ok", s.getMessage());
    }

    @Test
    void googleRpcStatusClassLoadsFromCommonProtosJar() {
        String location = Status.class.getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toString();
        assertTrue(
                location.contains("proto-google-common-protos"),
                "Expected com.google.rpc.Status to load from proto-google-common-protos jar, got: " + location);
    }
}
