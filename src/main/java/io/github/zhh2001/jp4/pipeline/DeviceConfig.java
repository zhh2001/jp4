package io.github.zhh2001.jp4.pipeline;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;

/**
 * Target-specific binary device configuration shipped alongside a P4Info to
 * {@code SetForwardingPipelineConfig}. The sealed hierarchy expresses the supported
 * targets at compile time; v0.1 ships {@code Bmv2} and {@code Raw}. Tofino support is
 * planned for v0.2 — until then, encode Tofino contexts manually and pass via
 * {@link Raw}.
 */
public sealed interface DeviceConfig permits DeviceConfig.Bmv2, DeviceConfig.Raw {

    /** Returns the bytes that go into {@code ForwardingPipelineConfig.p4_device_config}. */
    byte[] bytes();

    /** Empty payload — useful for targets that ignore device config. */
    static DeviceConfig empty() {
        return new Raw(new byte[0]);
    }

    /** BMv2 JSON program (output of {@code p4c --target bmv2}). */
    record Bmv2(byte[] json) implements DeviceConfig {
        public Bmv2 {
            Objects.requireNonNull(json, "json");
            json = json.clone();
        }

        public static Bmv2 fromFile(Path jsonPath) {
            Objects.requireNonNull(jsonPath, "jsonPath");
            try {
                return new Bmv2(Files.readAllBytes(jsonPath));
            } catch (IOException e) {
                throw new RuntimeException("Failed to read BMv2 JSON from " + jsonPath, e);
            }
        }

        @Override
        public byte[] json() {
            return json.clone();
        }

        @Override
        public byte[] bytes() {
            return json.clone();
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Bmv2 other && Arrays.equals(this.json, other.json);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(json);
        }

        @Override
        public String toString() {
            return "DeviceConfig.Bmv2(json[" + json.length + " bytes])";
        }
    }

    /** Pre-encoded device config bytes for any target jp4 doesn't model directly. */
    record Raw(byte[] bytes) implements DeviceConfig {
        public Raw {
            Objects.requireNonNull(bytes, "bytes");
            bytes = bytes.clone();
        }

        public static Raw fromFile(Path path) {
            Objects.requireNonNull(path, "path");
            try {
                return new Raw(Files.readAllBytes(path));
            } catch (IOException e) {
                throw new RuntimeException("Failed to read device config from " + path, e);
            }
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Raw other && Arrays.equals(this.bytes, other.bytes);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(bytes);
        }

        @Override
        public String toString() {
            return "DeviceConfig.Raw(bytes[" + bytes.length + " bytes])";
        }
    }
}
