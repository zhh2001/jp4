package io.github.zhh2001.jp4.pipeline;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.TextFormat;
import io.github.zhh2001.jp4.error.P4PipelineException;
import p4.config.v1.P4InfoOuterClass;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Parsed P4Info plus a name-to-id index. Built once via one of the
 * {@code fromXxx} factories and cached on a {@code P4Switch} via
 * {@code bindPipeline} or {@code loadPipeline}; subsequent operations look up
 * tables / actions / match fields by name through the index this object owns.
 *
 * <p>Auto-sniffing in {@link #fromFile(Path)} / {@link #fromBytes(byte[])} tries
 * binary protobuf first (the common machine-generated form) and falls back to
 * P4Runtime text format on parse failure.
 *
 * @since 0.1.0
 */
public final class P4Info {

    private final P4InfoOuterClass.P4Info proto;
    private final Map<String, TableInfo> tablesByName;
    private final Map<String, ActionInfo> actionsByName;
    private final Map<Integer, TableInfo> tablesById;
    private final Map<Integer, ActionInfo> actionsById;
    private final List<PacketMetadataInfo> packetInMetadata;
    private final List<PacketMetadataInfo> packetOutMetadata;
    private final Map<String, PacketMetadataInfo> packetInByName;
    private final Map<String, PacketMetadataInfo> packetOutByName;
    private final Map<Integer, PacketMetadataInfo> packetInById;
    private final Map<Integer, PacketMetadataInfo> packetOutById;

    private P4Info(P4InfoOuterClass.P4Info proto) {
        this.proto = proto;
        this.tablesByName = buildTableIndex(proto);
        this.actionsByName = buildActionIndex(proto);
        Map<Integer, TableInfo> tById = new HashMap<>(this.tablesByName.size() * 2);
        for (TableInfo t : this.tablesByName.values()) tById.put(t.id(), t);
        this.tablesById = Map.copyOf(tById);
        Map<Integer, ActionInfo> aById = new HashMap<>(this.actionsByName.size() * 2);
        for (ActionInfo a : this.actionsByName.values()) aById.put(a.id(), a);
        this.actionsById = Map.copyOf(aById);

        // controller_packet_metadata: P4Info has zero or more declarations, each
        // with a header name ("packet_in" / "packet_out") and a list of fields.
        // jp4 surfaces only the two named declarations the spec defines for
        // PacketIn / PacketOut; other names (custom CPU headers etc.) are ignored.
        List<PacketMetadataInfo> in = new ArrayList<>();
        List<PacketMetadataInfo> out = new ArrayList<>();
        for (P4InfoOuterClass.ControllerPacketMetadata cpm : proto.getControllerPacketMetadataList()) {
            String name = cpm.getPreamble().getName();
            List<PacketMetadataInfo> sink = switch (name) {
                case "packet_in"  -> in;
                case "packet_out" -> out;
                default -> null;
            };
            if (sink == null) continue;
            for (P4InfoOuterClass.ControllerPacketMetadata.Metadata md : cpm.getMetadataList()) {
                sink.add(new PacketMetadataInfo(md.getId(), md.getName(), md.getBitwidth()));
            }
        }
        this.packetInMetadata  = List.copyOf(in);
        this.packetOutMetadata = List.copyOf(out);
        this.packetInByName   = indexByName(this.packetInMetadata);
        this.packetOutByName  = indexByName(this.packetOutMetadata);
        this.packetInById     = indexById(this.packetInMetadata);
        this.packetOutById    = indexById(this.packetOutMetadata);
    }

    private static Map<String, PacketMetadataInfo> indexByName(List<PacketMetadataInfo> list) {
        Map<String, PacketMetadataInfo> out = new HashMap<>(list.size() * 2);
        for (PacketMetadataInfo m : list) out.put(m.name(), m);
        return Map.copyOf(out);
    }

    private static Map<Integer, PacketMetadataInfo> indexById(List<PacketMetadataInfo> list) {
        Map<Integer, PacketMetadataInfo> out = new HashMap<>(list.size() * 2);
        for (PacketMetadataInfo m : list) out.put(m.id(), m);
        return Map.copyOf(out);
    }

    /**
     * Reads a P4Info file and auto-detects whether it is text-format or binary
     * protobuf. Throws {@link P4PipelineException} if neither parser succeeds.
     */
    public static P4Info fromFile(Path path) {
        Objects.requireNonNull(path, "path");
        try {
            return fromBytes(Files.readAllBytes(path));
        } catch (IOException e) {
            throw new P4PipelineException("could not read P4Info from " + path, e);
        }
    }

    /** Parses a text-format P4Info file. */
    public static P4Info fromText(Path path) {
        Objects.requireNonNull(path, "path");
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            return parseText(text);
        } catch (IOException e) {
            throw new P4PipelineException("could not read P4Info text from " + path, e);
        }
    }

    /** Parses a binary protobuf P4Info file. */
    public static P4Info fromBinary(Path path) {
        Objects.requireNonNull(path, "path");
        try {
            return parseBinary(Files.readAllBytes(path));
        } catch (IOException e) {
            throw new P4PipelineException("could not read P4Info binary from " + path, e);
        }
    }

    /**
     * Parses bytes; format is auto-detected. Binary protobuf is tried first
     * because it is the cheap fast path; text-format fallback runs only if the
     * binary parser rejects the input.
     */
    public static P4Info fromBytes(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        try {
            return parseBinary(bytes);
        } catch (P4PipelineException ignoredBinary) {
            // Fall through to text-format.
        }
        try {
            return parseText(new String(bytes, StandardCharsets.UTF_8));
        } catch (P4PipelineException textFailure) {
            throw new P4PipelineException(
                    "could not parse P4Info as binary protobuf or as text format", textFailure);
        }
    }

    private static P4Info parseBinary(byte[] bytes) {
        try {
            P4InfoOuterClass.P4Info parsed = P4InfoOuterClass.P4Info.parseFrom(bytes);
            return new P4Info(parsed);
        } catch (InvalidProtocolBufferException e) {
            throw new P4PipelineException("invalid binary P4Info", e);
        }
    }

    private static P4Info parseText(String text) {
        try {
            P4InfoOuterClass.P4Info.Builder b = P4InfoOuterClass.P4Info.newBuilder();
            TextFormat.merge(text, b);
            return new P4Info(b.build());
        } catch (TextFormat.ParseException e) {
            throw new P4PipelineException("invalid text-format P4Info", e);
        }
    }

    public List<String> tableNames() {
        return List.copyOf(tablesByName.keySet());
    }

    public List<String> actionNames() {
        return List.copyOf(actionsByName.keySet());
    }

    /**
     * Looks up a table by its fully-qualified name (e.g. {@code "MyIngress.ipv4_lpm"}).
     * Throws {@link P4PipelineException} if no such table exists in the bound P4Info.
     */
    public TableInfo table(String name) {
        TableInfo t = tablesByName.get(name);
        if (t == null) {
            throw new P4PipelineException(
                    "no table named '" + name + "' in P4Info (known: " + tablesByName.keySet() + ")");
        }
        return t;
    }

    public ActionInfo action(String name) {
        ActionInfo a = actionsByName.get(name);
        if (a == null) {
            throw new P4PipelineException(
                    "no action named '" + name + "' in P4Info (known: " + actionsByName.keySet() + ")");
        }
        return a;
    }

    /**
     * Reverse lookup: returns the action's fully-qualified name for a given numeric id,
     * or {@code null} if the P4Info has no action by that id. Used internally to render
     * "allowed actions" lists in validation error messages.
     */
    public String actionNameById(int id) {
        ActionInfo a = actionsById.get(id);
        return a == null ? null : a.name();
    }

    /**
     * Reverse lookup: returns the {@link TableInfo} for a given numeric table id, or
     * {@code null} if the P4Info has no table by that id. Used by the read-RPC reverse
     * parser when turning a {@code p4.v1.TableEntry} back into a jp4 {@link
     * io.github.zhh2001.jp4.entity.TableEntry}.
     */
    public TableInfo tableInfoById(int id) {
        return tablesById.get(id);
    }

    /**
     * Reverse lookup: returns the {@link ActionInfo} for a given numeric action id, or
     * {@code null} if the P4Info has no action by that id. Used by the read-RPC reverse
     * parser; complement of {@link #actionNameById}.
     */
    public ActionInfo actionInfoById(int id) {
        return actionsById.get(id);
    }

    /**
     * Fields declared on the {@code @controller_header("packet_in")} header in P4Info,
     * in declaration order. Empty list when the P4 program does not declare a
     * controller PacketIn header (in which case any received PacketIn message will
     * carry no metadata fields, only payload).
     */
    public List<PacketMetadataInfo> packetInMetadata() {
        return packetInMetadata;
    }

    /**
     * Fields declared on the {@code @controller_header("packet_out")} header in P4Info,
     * in declaration order. Empty list when the P4 program does not declare a
     * controller PacketOut header.
     */
    public List<PacketMetadataInfo> packetOutMetadata() {
        return packetOutMetadata;
    }

    /**
     * Looks up a PacketIn metadata field by name. Throws {@link P4PipelineException}
     * with the known-list message style used elsewhere in jp4 if the field is not
     * declared on the P4 program's {@code packet_in} controller header.
     */
    public PacketMetadataInfo packetInField(String name) {
        PacketMetadataInfo m = packetInByName.get(name);
        if (m == null) {
            throw new P4PipelineException(
                    "no PacketIn metadata field named '" + name + "' in P4Info "
                            + "(known: " + packetInByName.keySet() + ")");
        }
        return m;
    }

    /** Same as {@link #packetInField(String)} but for the {@code packet_out} header. */
    public PacketMetadataInfo packetOutField(String name) {
        PacketMetadataInfo m = packetOutByName.get(name);
        if (m == null) {
            throw new P4PipelineException(
                    "no PacketOut metadata field named '" + name + "' in P4Info "
                            + "(known: " + packetOutByName.keySet() + ")");
        }
        return m;
    }

    /**
     * Reverse lookup of a PacketIn metadata field by numeric id, or {@code null} when
     * the device sent an id that the bound P4Info does not declare. Used by the
     * inbound packet parser to surface device / P4Info drift loudly.
     */
    public PacketMetadataInfo packetInFieldById(int id) {
        return packetInById.get(id);
    }

    /** Reverse lookup for {@code packet_out}; null when unknown. */
    public PacketMetadataInfo packetOutFieldById(int id) {
        return packetOutById.get(id);
    }

    /** Internal access to the underlying parsed protobuf — used by P4Switch when
     *  building {@code SetForwardingPipelineConfigRequest}. */
    public P4InfoOuterClass.P4Info proto() {
        return proto;
    }

    /**
     * True when the P4Info has no tables and no actions defined — used by
     * {@code P4Switch.loadPipeline()} to decide whether the device is reporting
     * "no pipeline bound".
     */
    public boolean isEmpty() {
        return tablesByName.isEmpty() && actionsByName.isEmpty();
    }

    // ---------- index builders -----------------------------------------------

    private static Map<String, TableInfo> buildTableIndex(P4InfoOuterClass.P4Info proto) {
        Map<String, TableInfo> out = new HashMap<>(proto.getTablesCount() * 2);
        for (P4InfoOuterClass.Table t : proto.getTablesList()) {
            String name = t.getPreamble().getName();
            int id = t.getPreamble().getId();
            List<MatchFieldInfo> matchFields = new ArrayList<>(t.getMatchFieldsCount());
            for (P4InfoOuterClass.MatchField mf : t.getMatchFieldsList()) {
                matchFields.add(new MatchFieldInfo(
                        mf.getId(),
                        mf.getName(),
                        mapMatchKind(mf.getMatchType()),
                        mf.getBitwidth()));
            }
            Set<Integer> actionIds = new HashSet<>(t.getActionRefsCount() * 2);
            for (P4InfoOuterClass.ActionRef ar : t.getActionRefsList()) {
                actionIds.add(ar.getId());
            }
            out.put(name, new TableInfo(name, id, matchFields, actionIds, t.getSize()));
        }
        return Map.copyOf(out);
    }

    private static Map<String, ActionInfo> buildActionIndex(P4InfoOuterClass.P4Info proto) {
        Map<String, ActionInfo> out = new HashMap<>(proto.getActionsCount() * 2);
        for (P4InfoOuterClass.Action a : proto.getActionsList()) {
            String name = a.getPreamble().getName();
            int id = a.getPreamble().getId();
            List<ActionParamInfo> params = new ArrayList<>(a.getParamsCount());
            for (P4InfoOuterClass.Action.Param p : a.getParamsList()) {
                params.add(new ActionParamInfo(p.getId(), p.getName(), p.getBitwidth()));
            }
            out.put(name, new ActionInfo(name, id, params));
        }
        return Map.copyOf(out);
    }

    private static MatchFieldInfo.Kind mapMatchKind(P4InfoOuterClass.MatchField.MatchType type) {
        return switch (type) {
            case EXACT       -> MatchFieldInfo.Kind.EXACT;
            case LPM         -> MatchFieldInfo.Kind.LPM;
            case TERNARY     -> MatchFieldInfo.Kind.TERNARY;
            case RANGE       -> MatchFieldInfo.Kind.RANGE;
            case OPTIONAL    -> MatchFieldInfo.Kind.OPTIONAL;
            case UNSPECIFIED, UNRECOGNIZED -> MatchFieldInfo.Kind.UNSPECIFIED;
        };
    }
}
