package io.github.zhh2001.jp4;

import com.google.protobuf.ByteString;
import io.github.zhh2001.jp4.entity.ActionInstance;
import io.github.zhh2001.jp4.entity.TableEntry;
import io.github.zhh2001.jp4.match.Match;
import io.github.zhh2001.jp4.pipeline.ActionInfo;
import io.github.zhh2001.jp4.pipeline.ActionParamInfo;
import io.github.zhh2001.jp4.pipeline.MatchFieldInfo;
import io.github.zhh2001.jp4.pipeline.P4Info;
import io.github.zhh2001.jp4.pipeline.TableInfo;
import io.github.zhh2001.jp4.types.Bytes;
import p4.v1.P4RuntimeOuterClass.FieldMatch;
import p4.v1.P4RuntimeOuterClass.TableAction;

import java.util.Map;

/**
 * Package-private. Turns a {@link TableEntry} into the corresponding
 * {@code p4.v1.TableEntry} protobuf, resolving names → ids through the supplied
 * {@link P4Info} index. Canonicalises every byte sequence at the wire boundary
 * (matches and action params), per P4Runtime 1.3+ requirement.
 *
 * <p>Assumes {@link EntryValidator#validate} has already been called against the
 * same {@code P4Info}; this class trusts the entry's structure and only transcodes.
 */
final class EntryProto {

    private EntryProto() { }

    static p4.v1.P4RuntimeOuterClass.TableEntry toProto(TableEntry entry, P4Info p4info) {
        TableInfo table = p4info.table(entry.tableName());
        var b = p4.v1.P4RuntimeOuterClass.TableEntry.newBuilder()
                .setTableId(table.id());

        for (Map.Entry<String, Match> me : entry.matches().entrySet()) {
            MatchFieldInfo field = table.matchField(me.getKey());
            b.addMatch(buildFieldMatch(field, me.getValue()));
        }

        ActionInstance action = entry.action();
        if (action != null) {
            b.setAction(buildTableAction(action, p4info));
        }

        if (entry.priority() > 0) {
            b.setPriority(entry.priority());
        }

        return b.build();
    }

    private static FieldMatch buildFieldMatch(MatchFieldInfo field, Match m) {
        var fm = FieldMatch.newBuilder().setFieldId(field.id());
        switch (m) {
            case Match.Exact e -> fm.setExact(
                    FieldMatch.Exact.newBuilder()
                            .setValue(canonical(e.value()))
                            .build());
            case Match.Lpm l -> fm.setLpm(
                    FieldMatch.LPM.newBuilder()
                            .setValue(canonical(l.value()))
                            .setPrefixLen(l.prefixLen())
                            .build());
            case Match.Ternary t -> fm.setTernary(
                    FieldMatch.Ternary.newBuilder()
                            .setValue(canonical(t.value()))
                            .setMask(canonical(t.mask()))
                            .build());
            case Match.Range r -> fm.setRange(
                    FieldMatch.Range.newBuilder()
                            .setLow(canonical(r.low()))
                            .setHigh(canonical(r.high()))
                            .build());
            case Match.Optional o -> fm.setOptional(
                    FieldMatch.Optional.newBuilder()
                            .setValue(canonical(o.value()))
                            .build());
        }
        return fm.build();
    }

    private static TableAction buildTableAction(ActionInstance action, P4Info p4info) {
        ActionInfo info = p4info.action(action.name());
        var ab = p4.v1.P4RuntimeOuterClass.Action.newBuilder()
                .setActionId(info.id());
        for (Map.Entry<String, Bytes> p : action.params().entrySet()) {
            ActionParamInfo paramInfo = info.param(p.getKey());
            ab.addParams(p4.v1.P4RuntimeOuterClass.Action.Param.newBuilder()
                    .setParamId(paramInfo.id())
                    .setValue(canonical(p.getValue()))
                    .build());
        }
        return TableAction.newBuilder().setAction(ab.build()).build();
    }

    private static ByteString canonical(Bytes b) {
        return ByteString.copyFrom(b.canonical().toByteArray());
    }
}
