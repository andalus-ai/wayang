package tech.kayys.wayang.api.definition;

import tech.kayys.wayang.api.ApiException;

public enum DefinitionKind {
    /** BPMN process (native Map/JSON form, see ProcessMaps). */
    PROCESS,
    /** DMN decision model (see DmnLoader). */
    DECISION,
    /** Agent definition (see AgentSpecLoader). */
    AGENT,
    /** EIP pipeline, referenced inline by EIP tasks but stored for reuse/testing. */
    PIPELINE;

    public static DefinitionKind parse(String s) {
        try {
            return valueOf(s.toUpperCase(java.util.Locale.ROOT));
        } catch (RuntimeException e) {
            throw ApiException.badRequest("Unknown definition kind '" + s + "'");
        }
    }
}
