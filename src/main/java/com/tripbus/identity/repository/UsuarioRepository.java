package com.tripbus.identity.repository;

import com.tripbus.identity.model.Usuario;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface UsuarioRepository extends JpaRepository<Usuario, UUID>, JpaSpecificationExecutor<Usuario> {

    // Se compara lower(email) para usar el índice único uq_usuarios_email_lower (V5) en PostgreSQL.
    @Query("select u from Usuario u where lower(u.email) = lower(:email)")
    Optional<Usuario> findByEmail(@Param("email") String email);

    @Query("select count(u) > 0 from Usuario u where lower(u.email) = lower(:email)")
    boolean existsByEmail(@Param("email") String email);

    boolean existsByDocumentoIdentidad(String documentoIdentidad);
}
