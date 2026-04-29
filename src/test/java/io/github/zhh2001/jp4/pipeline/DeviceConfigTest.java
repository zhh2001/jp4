package io.github.zhh2001.jp4.pipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DeviceConfigTest {

    @Test
    void emptyHasNoBytes() {
        DeviceConfig empty = DeviceConfig.empty();
        assertEquals(0, empty.bytes().length);
    }

    @Test
    void bmv2WrapsBytesAndDefensiveCopies() {
        byte[] src = new byte[]{1, 2, 3};
        DeviceConfig.Bmv2 cfg = new DeviceConfig.Bmv2(src);
        src[0] = 99;
        assertArrayEquals(new byte[]{1, 2, 3}, cfg.json());
        // Mutating the accessor return doesn't affect internal state either.
        cfg.json()[0] = 99;
        assertArrayEquals(new byte[]{1, 2, 3}, cfg.bytes());
    }

    @Test
    void bmv2FromFileRoundTrips(@TempDir Path tmp) throws IOException {
        Path p = tmp.resolve("config.json");
        Files.writeString(p, "{}");
        DeviceConfig.Bmv2 cfg = DeviceConfig.Bmv2.fromFile(p);
        assertArrayEquals("{}".getBytes(), cfg.json());
    }

    @Test
    void rawFromFileRoundTrips(@TempDir Path tmp) throws IOException {
        Path p = tmp.resolve("blob.bin");
        Files.write(p, new byte[]{(byte) 0xde, (byte) 0xad});
        DeviceConfig.Raw cfg = DeviceConfig.Raw.fromFile(p);
        assertArrayEquals(new byte[]{(byte) 0xde, (byte) 0xad}, cfg.bytes());
    }

    @Test
    void equalsIsDeep() {
        assertEquals(new DeviceConfig.Bmv2(new byte[]{1, 2}), new DeviceConfig.Bmv2(new byte[]{1, 2}));
        assertNotEquals(new DeviceConfig.Bmv2(new byte[]{1, 2}), new DeviceConfig.Bmv2(new byte[]{1, 3}));
    }
}
