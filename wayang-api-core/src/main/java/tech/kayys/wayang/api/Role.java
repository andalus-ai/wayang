package tech.kayys.wayang.api;

import tech.kayys.wayang.api.definition.Studio;

import java.util.EnumSet;
import java.util.Set;

/** Coarse roles of the Studio / Management console; each grants a set of {@link Permission}s. */
public enum Role {
    /** Read-only: browse definitions, runs and tasks. */
    VIEWER(EnumSet.of(Permission.DEFINITION_READ, Permission.RUN_READ)),
    /** Operate: start/cancel runs, complete tasks, send messages. */
    OPERATOR(EnumSet.of(Permission.DEFINITION_READ, Permission.RUN_READ, Permission.RUN_START, Permission.RUN_CANCEL,
            Permission.TASK_COMPLETE, Permission.MESSAGE_SEND)),
    /** Author definitions (and test-run them). */
    DESIGNER(EnumSet.of(Permission.DEFINITION_READ, Permission.DEFINITION_WRITE, Permission.RUN_READ, Permission.RUN_START,
            Permission.RUN_CANCEL, Permission.TASK_COMPLETE, Permission.MESSAGE_SEND, Permission.SECRET_LIST)),
    ADMIN(EnumSet.allOf(Permission.class));

    private final Set<Permission> permissions;

    Role(Set<Permission> permissions) {
        this.permissions = permissions;
    }

    public Set<Permission> permissions() {
        return permissions;
    }
}
