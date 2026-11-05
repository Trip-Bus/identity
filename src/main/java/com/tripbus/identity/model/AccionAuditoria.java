package com.tripbus.identity.model;

/** US-105: acciones que quedan en admin_audit_log. ELIMINAR_CUENTA la hace el propio usuario (US-131). */
public enum AccionAuditoria {
    CAMBIAR_ROL,
    CREAR_USUARIO,
    SUSPENDER,
    REACTIVAR,
    DESBLOQUEAR,
    ELIMINAR_CUENTA
}
