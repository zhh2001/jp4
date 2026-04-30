package io.github.zhh2001.jp4;

import com.google.protobuf.ByteString;
import io.github.zhh2001.jp4.entity.ActionInstance;
import io.github.zhh2001.jp4.entity.TableEntry;
import io.github.zhh2001.jp4.entity.TableEntryBuilder;
import io.github.zhh2001.jp4.error.P4PipelineException;
import io.github.zhh2001.jp4.match.Match;
import io.github.zhh2001.jp4.pipeline.ActionInfo;
import io.github.zhh2001.jp4.pipeline.ActionParamInfo;
import io.github.zhh2001.jp4.pipeline.MatchFieldInfo;
import io.github.zhh2001.jp4.pipeline.P4Info;
import io.github.zhh2001.jp4.pipeline.TableInfo;
import io.github.zhh2001.jp4.types.Bytes;
import p4.v1.P4RuntimeOuterClass.Action;
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

    /**
     * Package-private: builds one {@code FieldMatch} from a jp4 {@link Match}, applied
     * canonical at the wire boundary. Reused by {@code ReadQueryImpl} when serialising
     * the match-filter half of a {@code ReadRequest}.
     */
    static FieldMatch matchToProto(MatchFieldInfo field, Match m) {
        return buildFieldMatch(field, m);
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

    // ---------- reverse: p4.v1.TableEntry → jp4 TableEntry --------------------

    /**
     * Reverse-parses a wire {@code p4.v1.TableEntry} from a Read response into a jp4
     * {@link TableEntry}, resolving every numeric id back to its declared name through
     * the supplied {@link P4Info} index. Per the read-side contract, no value-level
     * validation is performed — the device is trusted. Unknown ids (table / match field
     * / action / param) are surfaced as {@link P4PipelineException} so a P4Info / device
     * pipeline drift fails loud rather than silently dropping data.
     *
     * <p>Tables that are part of an action profile / selector return wire entries whose
     * {@code TableAction} carries an {@code action_profile_member_id} or
     * {@code action_profile_group_id} instead of an inline {@code Action} — these are
     * v0.2 work and surface as {@link P4PipelineException} with a descriptive message.
     * The v0.1 read RPC reads direct-action tables only.
     */
    static TableEntry fromProto(p4.v1.P4RuntimeOuterClass.TableEntry proto, P4Info p4info) {
        TableInfo table = p4info.tableInfoById(proto.getTableId());
        if (table == null) {
            throw new P4PipelineException(
                    "device returned table id " + proto.getTableId()
                            + " which is not in the bound P4Info; pipeline may have drifted "
                            + "since bindPipeline (known table ids: " + knownTableIds(p4info) + ")");
        }

        TableEntryBuilder builder = TableEntry.in(table.name());

        for (FieldMatch fm : proto.getMatchList()) {
            MatchFieldInfo field = table.matchFieldById(fm.getFieldId());
            if (field == null) {
                throw new P4PipelineException(
                        "device returned match field id " + fm.getFieldId() + " for table '"
                                + table.name() + "' which is not in the bound P4Info "
                                + "(known field ids: " + knownFieldIds(table) + ")");
            }
            builder.match(field.name(), buildMatch(fm, field));
        }

        if (proto.getPriority() > 0) {
            builder.priority(proto.getPriority());
        }

        if (!proto.hasAction()) {
            return builder.build();
        }
        return buildEntryWithAction(builder, proto.getAction(), table.name(), p4info);
    }

    private static Match buildMatch(FieldMatch fm, MatchFieldInfo field) {
        return switch (fm.getFieldMatchTypeCase()) {
            case EXACT    -> new Match.Exact(Bytes.of(fm.getExact().getValue().toByteArray()));
            case LPM      -> new Match.Lpm(
                    Bytes.of(fm.getLpm().getValue().toByteArray()),
                    fm.getLpm().getPrefixLen());
            case TERNARY  -> new Match.Ternary(
                    Bytes.of(fm.getTernary().getValue().toByteArray()),
                    Bytes.of(fm.getTernary().getMask().toByteArray()));
            case RANGE    -> new Match.Range(
                    Bytes.of(fm.getRange().getLow().toByteArray()),
                    Bytes.of(fm.getRange().getHigh().toByteArray()));
            case OPTIONAL -> new Match.Optional(
                    Bytes.of(fm.getOptional().getValue().toByteArray()));
            case OTHER, FIELDMATCHTYPE_NOT_SET -> throw new P4PipelineException(
                    "device returned FieldMatch with no recognised match type for field '"
                            + field.name() + "' (oneof case: " + fm.getFieldMatchTypeCase() + ")");
        };
    }

    private static TableEntry buildEntryWithAction(TableEntryBuilder builder,
                                                   TableAction tableAction,
                                                   String tableName,
                                                   P4Info p4info) {
        switch (tableAction.getTypeCase()) {
            case ACTION -> {
                Action action = tableAction.getAction();
                ActionInfo actionInfo = p4info.actionInfoById(action.getActionId());
                if (actionInfo == null) {
                    throw new P4PipelineException(
                            "device returned action id " + action.getActionId() + " for table '"
                                    + tableName + "' which is not in the bound P4Info "
                                    + "(known action ids: " + knownActionIds(p4info) + ")");
                }
                var ab = builder.action(actionInfo.name());
                for (Action.Param p : action.getParamsList()) {
                    ActionParamInfo paramInfo = actionInfo.paramById(p.getParamId());
                    if (paramInfo == null) {
                        throw new P4PipelineException(
                                "device returned param id " + p.getParamId() + " for action '"
                                        + actionInfo.name() + "' which is not in the bound P4Info "
                                        + "(known param ids: " + knownParamIds(actionInfo) + ")");
                    }
                    ab.param(paramInfo.name(), Bytes.of(p.getValue().toByteArray()));
                }
                return ab.build();
            }
            case ACTION_PROFILE_MEMBER_ID, ACTION_PROFILE_GROUP_ID, ACTION_PROFILE_ACTION_SET ->
                    throw new P4PipelineException(
                            "device returned an action-profile entry for table '" + tableName
                                    + "' (" + tableAction.getTypeCase()
                                    + "); action profile / selector reads are v0.2 work");
            case TYPE_NOT_SET -> throw new P4PipelineException(
                    "device returned TableAction with no type set for table '" + tableName + "'");
        }
        throw new IllegalStateException("unreachable");
    }

    private static java.util.List<Integer> knownTableIds(P4Info p4info) {
        java.util.List<Integer> ids = new java.util.ArrayList<>();
        for (String n : p4info.tableNames()) ids.add(p4info.table(n).id());
        java.util.Collections.sort(ids);
        return ids;
    }

    private static java.util.List<Integer> knownFieldIds(TableInfo table) {
        java.util.List<Integer> ids = new java.util.ArrayList<>();
        for (MatchFieldInfo mf : table.matchFields()) ids.add(mf.id());
        java.util.Collections.sort(ids);
        return ids;
    }

    private static java.util.List<Integer> knownActionIds(P4Info p4info) {
        java.util.List<Integer> ids = new java.util.ArrayList<>();
        for (String n : p4info.actionNames()) ids.add(p4info.action(n).id());
        java.util.Collections.sort(ids);
        return ids;
    }

    private static java.util.List<Integer> knownParamIds(ActionInfo actionInfo) {
        java.util.List<Integer> ids = new java.util.ArrayList<>();
        for (ActionParamInfo p : actionInfo.params()) ids.add(p.id());
        java.util.Collections.sort(ids);
        return ids;
    }
}
