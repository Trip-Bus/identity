package com.tripbus.identity.repository;

import com.tripbus.identity.model.SesionRevocada;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface SesionRevocadaRepository extends JpaRepository<SesionRevocada, String> {

    @Modifying
    @Query("delete from SesionRevocada s where s.expiraEn < :ahora")
    int deleteExpiradas(@Param("ahora") Instant ahora);
}
