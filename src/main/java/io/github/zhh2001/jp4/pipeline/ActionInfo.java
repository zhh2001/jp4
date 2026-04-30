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

    ActionInfo(String name, int id, List<ActionParamInfo> params) {
        this.name = Objects.requireNonNull(name, "name");
        this.id = id;
        this.params = List.copyOf(params);

        Map<String, ActionParamInfo> idx = new HashMap<>(this.params.size() * 2);
        for (ActionParamInfo p : this.params) {
            idx.put(p.name(), p);
        }
        this.paramsByName = Map.copyOf(idx);
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

    @Override
    public String toString() {
        return "ActionInfo(name=" + name + ", id=" + id + ", params=" + params.size() + ")";
    }
}
