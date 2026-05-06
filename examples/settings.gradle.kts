/*
 * jp4 examples — Gradle composite build.
 *
 * `includeBuild("..")` wires the live jp4 source tree as a dependency
 * substitution: any `implementation("io.github.zhh2001:jp4:...")` declared by
 * an example module resolves to the in-tree jp4 main artifact, not a
 * published Maven Central release. Examples therefore exercise the same
 * code that the rest of the test suite exercises, with no publish-to-Maven
 * round-trip.
 *
 * Test fixtures (BMv2TestSupport, NativeBackend, DockerBackend, Awaitility,
 * etc.) are deliberately NOT pulled in — examples only depend on jp4's main
 * artifact. A user wiring up their own BMv2 has no reason to inherit jp4's
 * test infrastructure.
 */

rootProject.name = "jp4-examples"

includeBuild("..")

include(":simple-l2-switch")
include(":simple-loadbalancer")
include(":network-monitor")
