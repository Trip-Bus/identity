package com.tripbus.identity.repository;

import com.tripbus.identity.model.RegistroAuditoria;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.UUID;

public interface AuditoriaRepository extends JpaRepository<RegistroAuditoria, UUID>,
        JpaSpecificationExecutor<RegistroAuditoria> {
}
