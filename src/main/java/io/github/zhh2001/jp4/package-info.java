/**
 * jp4's top-level API: the {@link io.github.zhh2001.jp4.P4Switch} class
 * (one client connection to one P4Runtime device) and the surrounding
 * connection / pipeline / stream-message types.
 *
 * <p>Most jp4 programs touch four classes from this package:
 *
 * <ul>
 *   <li>{@link io.github.zhh2001.jp4.P4Switch} — the controller. Carries
 *       insert / modify / delete / batch / read / send / receive
 *       methods. {@code AutoCloseable}.</li>
 *   <li>{@link io.github.zhh2001.jp4.Connector} — fluent builder for
 *       {@code P4Switch}. Returned by
 *       {@link io.github.zhh2001.jp4.P4Switch#connect(String)}.</li>
 *   <li>{@link io.github.zhh2001.jp4.ReadQuery} — name-based read
 *       builder. Returned by {@code sw.read(tableName)}; terminates in
 *       {@code .all()} / {@code .one()} / {@code .stream()}.</li>
 *   <li>{@link io.github.zhh2001.jp4.BatchBuilder} — multi-update Write
 *       accumulator. Returned by {@code sw.batch()}; terminates in
 *       {@code .execute()}.</li>
 * </ul>
 *
 * <p>The result types {@link io.github.zhh2001.jp4.WriteResult} and
 * {@link io.github.zhh2001.jp4.MastershipStatus} also live here.
 * Exceptions are in {@code io.github.zhh2001.jp4.error}; data carriers
 * (TableEntry, PacketIn, PacketOut, …) in {@code io.github.zhh2001.jp4.entity};
 * pipeline / P4Info metadata in {@code io.github.zhh2001.jp4.pipeline};
 * value types (Bytes, Mac, Ip4, Ip6, ElectionId) in
 * {@code io.github.zhh2001.jp4.types}.
 *
 * <p>See the project README and the {@code docs/} directory in the
 * source tree for tutorial-style guides.
 *
 * @since 0.1.0
 */
package io.github.zhh2001.jp4;
