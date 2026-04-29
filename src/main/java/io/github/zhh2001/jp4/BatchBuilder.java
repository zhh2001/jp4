package io.github.zhh2001.jp4;

import io.github.zhh2001.jp4.entity.TableEntry;

/**
 * Accumulator for one batched {@code Write} RPC, returned by
 * {@code P4Switch.batch()}. Add updates with {@link #insert(TableEntry)},
 * {@link #modify(TableEntry)}, {@link #delete(TableEntry)} (chainable), then
 * {@link #execute()} to fire the RPC and obtain a {@link WriteResult}.
 */
public interface BatchBuilder {

    BatchBuilder insert(TableEntry e);

    BatchBuilder modify(TableEntry e);

    /** For deletes, only the entry's match key is used; the action half is ignored. */
    BatchBuilder delete(TableEntry e);

    /**
     * Sends the accumulated updates as one Write RPC. A whole-RPC failure (transport,
     * mastership lost) throws {@code P4ConnectionException}; per-update rejections are
     * reported in {@link WriteResult#failures()}.
     */
    WriteResult execute();
}
