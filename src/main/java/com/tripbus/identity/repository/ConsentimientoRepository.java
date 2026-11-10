package com.tripbus.identity.repository;

import com.tripbus.identity.model.Consentimiento;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ConsentimientoRepository extends JpaRepository<Consentimiento, UUID> {

    List<Consentimiento> findByUsuarioId(UUID usuarioId);

    /** US-131: retiro del consentimiento de todas las políticas aún vigentes. */
    @Modifying
    @Query("update Consentimiento c set c.revocadoEn = :en where c.usuarioId = :usuarioId and c.revocadoEn is null")
    int revocarVigentes(@Param("usuarioId") UUID usuarioId, @Param("en") Instant en);
}
