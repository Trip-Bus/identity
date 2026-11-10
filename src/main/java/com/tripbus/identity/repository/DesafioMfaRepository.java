package com.tripbus.identity.repository;

import com.tripbus.identity.model.DesafioMfa;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface DesafioMfaRepository extends JpaRepository<DesafioMfa, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from DesafioMfa d where d.tokenHash = :hash")
    Optional<DesafioMfa> findLockedByTokenHash(@Param("hash") String hash);

    @Modifying
    @Query("delete from DesafioMfa d where d.usuarioId = :usuarioId")
    int borrarDeUsuario(@Param("usuarioId") UUID usuarioId);

    @Modifying
    @Query("delete from DesafioMfa d where d.expiraEn < :corte")
    int borrarVencidosAntesDe(@Param("corte") Instant corte);
}
