package com.tripbus.identity.repository;

import com.tripbus.identity.model.Consentimiento;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ConsentimientoRepository extends JpaRepository<Consentimiento, UUID> {

    List<Consentimiento> findByUsuarioId(UUID usuarioId);
}
