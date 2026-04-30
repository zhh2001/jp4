package io.github.zhh2001.jp4;

import io.github.zhh2001.jp4.entity.ActionInstance;
import io.github.zhh2001.jp4.entity.TableEntry;
import io.github.zhh2001.jp4.error.OperationType;
import io.github.zhh2001.jp4.error.P4PipelineException;
import io.github.zhh2001.jp4.match.Match;
import io.github.zhh2001.jp4.pipeline.ActionInfo;
import io.github.zhh2001.jp4.pipeline.ActionParamInfo;
import io.github.zhh2001.jp4.pipeline.MatchFieldInfo;
import io.github.zhh2001.jp4.pipeline.P4Info;
import io.github.zhh2001.jp4.pipeline.TableInfo;
import io.github.zhh2001.jp4.types.Bytes;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Package-private validator that checks a {@link TableEntry} against a bound
 * {@link P4Info} before a write or read RPC is sent. Six rules are enforced (see
 * the body of {@link #validate}); each failure throws {@link P4PipelineException}
 * with a "known-list" message naming the candidate set, so a typo at the call site
 * is diagnosed without the user reading the P4Info file. This compensates for
 * deferring P4Info validation out of {@code TableEntry.in(...)} construction.
 */
final class EntryValidator {

    private EntryValidator() { }

    /**
     * Runs all P4Info-driven checks against {@code entry}. {@code op} controls one
     * action-presence rule: {@code INSERT}/{@code MODIFY} require an action,
     * {@code DELETE} accepts a null action and ignores any action that is present.
     */
    static void validate(TableEntry entry, P4Info p4info, OperationType op) {
        TableInfo table = lookupTable(entry.tableName(), p4info);

        validateActionPresence(entry, op);
        validateMatchFields(entry, table, p4info);
        if (entry.action() != null) {
            validateAction(entry.action(), table, p4info);
        }
        validatePriority(entry, table);
    }

    /**
     * Public-internal: number of significant bits in a non-negative big-endian byte
     * sequence. Used at write-RPC build time to verify that user-supplied values fit
     * the declared field / parameter bit width. {@code null} or empty input → 0.
     *
     * <p>Algorithm: scan left-to-right for the first non-zero byte; the bit width is
     * {@code (bytes_after_first_nonzero × 8) + position_of_high_bit_in_that_byte}.
     * Pure data, no allocations — easy to unit-test on edge cases.
     */
    static int actualBitWidth(byte[] value) {
        if (value == null || value.length == 0) return 0;
        int idx = -1;
        for (int i = 0; i < value.length; i++) {
            if (value[i] != 0) {
                idx = i;
                break;
            }
        }
        if (idx == -1) return 0;
        int leadingByte = value[idx] & 0xff;
        int highBit = 32 - Integer.numberOfLeadingZeros(leadingByte);   // 1..8 for nonzero byte
        int remainingBytes = value.length - idx - 1;
        return remainingBytes * 8 + highBit;
    }

    // ---------- individual rules --------------------------------------------

    private static TableInfo lookupTable(String tableName, P4Info p4info) {
        try {
            return p4info.table(tableName);
        } catch (P4PipelineException notFound) {
            // P4Info already provides a known-list in its message; bubble up.
            throw notFound;
        }
    }

    private static void validateActionPresence(TableEntry entry, OperationType op) {
        ActionInstance action = entry.action();
        if ((op == OperationType.INSERT || op == OperationType.MODIFY) && action == null) {
            throw new P4PipelineException(
                    "Operation " + op + " requires an action; entry for table '"
                            + entry.tableName() + "' was built without .action(...). "
                            + "Add an action via the builder, or use sw.delete(...) for "
                            + "delete-by-key entries.");
        }
        // DELETE: action presence is irrelevant; if present, it's silently ignored.
    }

    private static void validateMatchFields(TableEntry entry, TableInfo table, P4Info p4info) {
        for (Map.Entry<String, Match> e : entry.matches().entrySet()) {
            String fieldName = e.getKey();
            Match m = e.getValue();
            MatchFieldInfo field = lookupMatchField(fieldName, table);
            requireMatchKindMatches(field, m, table.name());
            requireValueFits(field, m);
        }
    }

    private static MatchFieldInfo lookupMatchField(String fieldName, TableInfo table) {
        try {
            return table.matchField(fieldName);
        } catch (P4PipelineException notFound) {
            List<String> known = new ArrayList<>(table.matchFields().size());
            for (MatchFieldInfo mf : table.matchFields()) known.add(mf.name());
            throw new P4PipelineException(
                    "Field '" + fieldName + "' not found in table '" + table.name()
                            + "'. Known fields: " + known);
        }
    }

    private static void requireMatchKindMatches(MatchFieldInfo field, Match m, String tableName) {
        MatchFieldInfo.Kind expected = field.matchKind();
        MatchFieldInfo.Kind actual = switch (m) {
            case Match.Exact e    -> MatchFieldInfo.Kind.EXACT;
            case Match.Lpm l      -> MatchFieldInfo.Kind.LPM;
            case Match.Ternary t  -> MatchFieldInfo.Kind.TERNARY;
            case Match.Range r    -> MatchFieldInfo.Kind.RANGE;
            case Match.Optional o -> MatchFieldInfo.Kind.OPTIONAL;
        };
        if (expected != actual) {
            throw new P4PipelineException(
                    "Match kind mismatch for '" + field.name() + "' in table '" + tableName
                            + "': table declares " + expected + ", got " + actual);
        }
    }

    private static void requireValueFits(MatchFieldInfo field, Match m) {
        switch (m) {
            case Match.Exact e -> requireBitWidth(e.value(), field.bitWidth(), "field '" + field.name() + "'");
            case Match.Lpm l -> {
                if (l.prefixLen() > field.bitWidth()) {
                    throw new P4PipelineException(
                            "LPM prefixLen " + l.prefixLen() + " exceeds field '" + field.name()
                                    + "' bitWidth " + field.bitWidth());
                }
                requireBitWidth(l.value(), field.bitWidth(), "field '" + field.name() + "'");
            }
            case Match.Ternary t -> {
                requireBitWidth(t.value(), field.bitWidth(), "field '" + field.name() + "' (ternary value)");
                requireBitWidth(t.mask(),  field.bitWidth(), "field '" + field.name() + "' (ternary mask)");
            }
            case Match.Range r -> {
                requireBitWidth(r.low(),  field.bitWidth(), "field '" + field.name() + "' (range low)");
                requireBitWidth(r.high(), field.bitWidth(), "field '" + field.name() + "' (range high)");
            }
            case Match.Optional o ->
                requireBitWidth(o.value(), field.bitWidth(), "field '" + field.name() + "' (optional)");
        }
    }

    private static void validateAction(ActionInstance action, TableInfo table, P4Info p4info) {
        ActionInfo actionInfo;
        try {
            actionInfo = p4info.action(action.name());
        } catch (P4PipelineException notFound) {
            throw notFound;     // P4Info already lists known actions
        }
        if (!table.actionIds().contains(actionInfo.id())) {
            List<String> allowed = new ArrayList<>(table.actionIds().size());
            for (Integer id : new TreeSet<>(table.actionIds())) {
                String n = p4info.actionNameById(id);
                allowed.add(n != null ? n : "<unknown id=" + id + ">");
            }
            throw new P4PipelineException(
                    "Action '" + action.name() + "' not part of action set for table '"
                            + table.name() + "'. Allowed actions: " + allowed);
        }
        for (Map.Entry<String, Bytes> p : action.params().entrySet()) {
            String paramName = p.getKey();
            Bytes paramVal = p.getValue();
            ActionParamInfo paramInfo = lookupActionParam(paramName, actionInfo);
            requireBitWidth(paramVal, paramInfo.bitWidth(),
                    "action '" + actionInfo.name() + "' param '" + paramName + "'");
        }
    }

    private static ActionParamInfo lookupActionParam(String paramName, ActionInfo actionInfo) {
        try {
            return actionInfo.param(paramName);
        } catch (P4PipelineException notFound) {
            List<String> known = new ArrayList<>(actionInfo.params().size());
            for (ActionParamInfo p : actionInfo.params()) known.add(p.name());
            throw new P4PipelineException(
                    "Param '" + paramName + "' not found in action '" + actionInfo.name()
                            + "'. Known params: " + known);
        }
    }

    private static void validatePriority(TableEntry entry, TableInfo table) {
        boolean needsPriority = false;
        for (MatchFieldInfo mf : table.matchFields()) {
            if (mf.matchKind() == MatchFieldInfo.Kind.TERNARY
                    || mf.matchKind() == MatchFieldInfo.Kind.RANGE
                    || mf.matchKind() == MatchFieldInfo.Kind.OPTIONAL) {
                needsPriority = true;
                break;
            }
        }
        if (needsPriority && entry.priority() <= 0) {
            throw new P4PipelineException(
                    "table '" + table.name() + "' has ternary/range/optional match fields "
                            + "and requires priority > 0; got " + entry.priority()
                            + " (call .priority(...) on the builder)");
        }
    }

    private static void requireBitWidth(Bytes value, int declared, String context) {
        int actual = actualBitWidth(value.toByteArray());
        if (actual > declared) {
            throw new P4PipelineException(
                    "value width " + actual + " bits exceeds " + context
                            + " bitWidth " + declared);
        }
    }
}
