package io.github.zhh2001.jp4.pipeline;

import io.github.zhh2001.jp4.error.P4PipelineException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Read-only metadata for one P4 action, derived from P4Info.
 *
 * @since 0.1.0
 */
public final class ActionInfo {

    private final String name;
    private final int id;
    private final List<ActionParamInfo> params;
    private final Map<String, ActionParamInfo> paramsByName;
    private final Map<Integer, ActionParamInfo> paramsById;

    ActionInfo(String name, int id, List<ActionParamInfo> params) {
        this.name = Objects.requireNonNull(name, "name");
        this.id = id;
        this.params = List.copyOf(params);

        Map<String, ActionParamInfo> idx = new HashMap<>(this.params.size() * 2);
        Map<Integer, ActionParamInfo> idxById = new HashMap<>(this.params.size() * 2);
        for (ActionParamInfo p : this.params) {
            idx.put(p.name(), p);
            idxById.put(p.id(), p);
        }
        this.paramsByName = Map.copyOf(idx);
        this.paramsById = Map.copyOf(idxById);
    }

    public String name() {
        return name;
    }

    public int id() {
        return id;
    }

    public List<ActionParamInfo> params() {
        return params;
    }

    /**
     * Looks up one parameter by name. Throws {@link P4PipelineException} if the
     * action has no parameter by that name.
     */
    public ActionParamInfo param(String paramName) {
        ActionParamInfo p = paramsByName.get(paramName);
        if (p == null) {
            throw new P4PipelineException(
                    "action " + name + " has no parameter named '" + paramName
                            + "' (known: " + paramsByName.keySet() + ")");
        }
        return p;
    }

    /**
     * Reverse lookup: returns the {@link ActionParamInfo} for a given numeric param id,
     * or {@code null} if the action has no parameter by that id. Used by the read-RPC
     * reverse parser to map a {@code p4.v1.Action.Param} back to its declared name and
     * bit width.
     */
    public ActionParamInfo paramById(int id) {
        return paramsById.get(id);
    }

    @Override
    public String toString() {
        return "ActionInfo(name=" + name + ", id=" + id + ", params=" + params.size() + ")";
    }
}
