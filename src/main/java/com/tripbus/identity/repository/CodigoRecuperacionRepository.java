package com.tripbus.identity.repository;

import com.tripbus.identity.model.CodigoRecuperacion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface CodigoRecuperacionRepository extends JpaRepository<CodigoRecuperacion, UUID> {

    Optional<CodigoRecuperacion> findFirstByUsuarioIdAndCodigoHashAndUsadoEnIsNull(UUID usuarioId, String codigoHash);

    @Modifying
    @Query("delete from CodigoRecuperacion c where c.usuarioId = :usuarioId")
    int borrarDeUsuario(@Param("usuarioId") UUID usuarioId);
}
