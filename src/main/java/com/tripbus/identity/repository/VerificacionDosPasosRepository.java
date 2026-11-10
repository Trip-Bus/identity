package com.tripbus.identity.repository;

import com.tripbus.identity.model.VerificacionDosPasos;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface VerificacionDosPasosRepository extends JpaRepository<VerificacionDosPasos, UUID> {

    boolean existsByUsuarioIdAndActivaTrue(UUID usuarioId);

    /** Bloquea la fila: dos peticiones con el mismo código no pueden aceptarlo las dos. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from VerificacionDosPasos m where m.usuarioId = :usuarioId")
    Optional<VerificacionDosPasos> findLockedByUsuarioId(@Param("usuarioId") UUID usuarioId);
}
