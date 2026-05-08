package io.github.zhh2001.jp4.pipeline;

import java.io.IOException;
import java.io.UncheckedIOException;
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
 *
 * <p>Both variants are immutable records, safe to share across threads. The
 * byte[]-bearing canonical constructors of {@link Bmv2} and {@link Raw}
 * defensively copy on construction; the {@link #bytes()} accessor returns a
 * fresh defensive copy on each call.
 *
 * @since 0.1.0
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

        /**
         * Loads a BMv2 JSON config from disk. The file at {@code jsonPath} is read
         * in full; large multi-megabyte device configs are kept in memory by the
         * returned {@code Bmv2} value.
         *
         * @param jsonPath path to the BMv2 JSON file (typically the
         *                 {@code .json} output of {@code p4c --target bmv2})
         * @return a {@code Bmv2} wrapping the file's bytes
         * @throws UncheckedIOException if the file cannot be read; the underlying
         *         {@link IOException} is preserved as the cause
         */
        public static Bmv2 fromFile(Path jsonPath) {
            Objects.requireNonNull(jsonPath, "jsonPath");
            try {
                return new Bmv2(Files.readAllBytes(jsonPath));
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to read BMv2 JSON from " + jsonPath, e);
            }
        }

        /**
         * Wraps a BMv2 JSON byte array (typically the output of
         * {@code p4c --target bmv2 --arch v1model -o ...json ...}) as a {@code Bmv2}
         * device config. For loading from a file path use {@link #fromFile(Path)};
         * use this factory when the JSON is already in memory (resource bundle,
         * in-memory cache, network fetch).
         *
         * <p>The input is defensively copied; later mutation of the supplied array
         * does not affect the constructed {@code Bmv2}. An empty byte array is a
         * valid input that produces an empty config — semantically a "no device
         * config" payload, equivalent to {@link DeviceConfig#empty()}.
         *
         * @param json the BMv2 JSON content (UTF-8 bytes)
         * @return a {@code Bmv2} wrapping a defensive copy of {@code json}
         * @throws NullPointerException if {@code json} is null
         * @since 1.0.0
         */
        public static Bmv2 fromBytes(byte[] json) {
            return new Bmv2(json);
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

        /**
         * Loads a target-specific device config from disk. The file at {@code path}
         * is read in full and passed to the device verbatim.
         *
         * @param path path to the device config file
         * @return a {@code Raw} wrapping the file's bytes
         * @throws UncheckedIOException if the file cannot be read; the underlying
         *         {@link IOException} is preserved as the cause
         */
        public static Raw fromFile(Path path) {
            Objects.requireNonNull(path, "path");
            try {
                return new Raw(Files.readAllBytes(path));
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to read device config from " + path, e);
            }
        }

        /**
         * Wraps a pre-encoded device-config byte array as a {@code Raw}. For
         * loading from a file path use {@link #fromFile(Path)}; use this factory
         * when the bytes are already in memory.
         *
         * <p>{@code Raw} performs no parsing or validation — the bytes are passed
         * to the device verbatim. The input is defensively copied; later mutation
         * of the supplied array does not affect the constructed {@code Raw}. An
         * empty byte array is a valid input, equivalent to
         * {@link DeviceConfig#empty()}.
         *
         * @param bytes the target-specific device-config payload
         * @return a {@code Raw} wrapping a defensive copy of {@code bytes}
         * @throws NullPointerException if {@code bytes} is null
         * @since 1.0.0
         */
        public static Raw fromBytes(byte[] bytes) {
            return new Raw(bytes);
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
