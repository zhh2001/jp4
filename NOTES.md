# Internal engineering notes

This file is for decisions and observations that will eventually find their way into
a public README, but for now live close to the code so contributors don't lose them.

## JDK 24+ and `grpc-netty-shaded`

`grpc-netty-shaded` (Netty 4.x repackaged) touches two JVM features that JDK 24 and
later treat as restricted:

1. **`sun.misc.Unsafe.allocateMemory`** — used by Netty's `PlatformDependent0` for
   off-heap buffers. JDK 24 surfaces a "terminally deprecated" warning; some future
   release will block the call.
2. **`System.loadLibrary`** — used by Netty's `NativeLibraryUtil` to load the bundled
   epoll JNI library. JDK 24 surfaces a "restricted method called" warning.

### Classification

On the JDK 25 reference machine these are **banner warnings only**: the calls succeed,
the epoll library loads, and gRPC channels function normally. End-to-end smoke covered
by `JdkNettyCompatibilityTest` (a real `NettyChannelBuilder` channel issuing one RPC
to a closed port and getting `UNAVAILABLE`).

### Mitigation in this repo

`tasks.test` in `build.gradle.kts` passes `--enable-native-access=ALL-UNNAMED`, which
acknowledges the access at JVM startup and silences the second warning. The `Unsafe`
warning is upstream-Netty's to fix; until then it is benign.

### Recommendation for downstream users

Apps that depend on jp4 should add the same JVM flag to their own runtime when on
JDK 24+:

```sh
java --enable-native-access=ALL-UNNAMED -jar my-controller.jar
```

This will be folded into the official README in Phase 10.
