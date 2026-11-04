package com.tripbus.identity.service;

import com.tripbus.identity.dto.AuditoriaResponse;
import com.tripbus.identity.dto.PageResponse;
import com.tripbus.identity.model.AccionAuditoria;
import com.tripbus.identity.model.RegistroAuditoria;
import com.tripbus.identity.model.RolUsuario;
import com.tripbus.identity.model.Usuario;
import com.tripbus.identity.repository.AuditoriaRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** US-105: guardar y consultar la auditoría. */
class AuditoriaServiceTests {

    private final AuditoriaRepository repository = mock(AuditoriaRepository.class);
    private final AuditoriaService service = new AuditoriaService(repository);

    @Test
    void registrarGuardaActorAccionObjetivoAntesDespuesYMotivo() {
        UUID actor = UUID.randomUUID();
        UUID objetivo = UUID.randomUUID();
        ArgumentCaptor<RegistroAuditoria> guardado = ArgumentCaptor.forClass(RegistroAuditoria.class);

        service.registrar(actor, AccionAuditoria.SUSPENDER, objetivo, Map.of("activo", true), Map.of("activo", false),
                "Fraude");

        verify(repository).save(guardado.capture());
        RegistroAuditoria r = guardado.getValue();
        assertThat(r.getId()).isNotNull();
        assertThat(r.getActorId()).isEqualTo(actor);
        assertThat(r.getAccion()).isEqualTo(AccionAuditoria.SUSPENDER);
        assertThat(r.getTargetUserId()).isEqualTo(objetivo);
        assertThat(r.getAntes()).containsEntry("activo", true);
        assertThat(r.getDespues()).containsEntry("activo", false);
        assertThat(r.getMotivo()).isEqualTo("Fraude");
        assertThat(r.getCreadoEn()).isBeforeOrEqualTo(Instant.now());
    }

    @Test
    @SuppressWarnings("unchecked")
    void buscarPaginaDelMasRecienteAlMasAntiguoYLimitaElTamano() {
        RegistroAuditoria registro = new RegistroAuditoria(UUID.randomUUID(), AccionAuditoria.CAMBIAR_ROL,
                UUID.randomUUID(), Map.of("rol", "PASAJERO"), Map.of("rol", "OPERADOR"), null, Instant.now());
        ArgumentCaptor<Pageable> pagina = ArgumentCaptor.forClass(Pageable.class);
        when(repository.findAll(any(Specification.class), pagina.capture()))
                .thenAnswer(inv -> new PageImpl<>(List.of(registro), inv.getArgument(1), 1));

        PageResponse<AuditoriaResponse> r = service.buscar(null, null, AccionAuditoria.CAMBIAR_ROL, null, null, -3, 500);

        assertThat(pagina.getValue().getPageNumber()).isZero();
        assertThat(pagina.getValue().getPageSize()).isEqualTo(100);
        assertThat(pagina.getValue().getSort()).isEqualTo(
                Sort.by(Sort.Order.desc("creadoEn"), Sort.Order.desc("id")));
        assertThat(r.totalElements()).isEqualTo(1);
        assertThat(r.content()).singleElement().satisfies(a -> {
            assertThat(a.accion()).isEqualTo(AccionAuditoria.CAMBIAR_ROL);
            assertThat(a.despues()).containsEntry("rol", "OPERADOR");
        });

        service.buscar(null, null, null, null, null, 0, 0);
        assertThat(pagina.getValue().getPageSize()).isEqualTo(20);
    }

    @Test
    void elEstadoAuditadoNoLlevaDatosPersonales() {
        Usuario u = new Usuario("ana@tripbus.co", "hash", "CC-1", "Ana");
        u.setRol(RolUsuario.OPERADOR);
        UUID empresa = UUID.randomUUID();
        u.setCompanyId(empresa);

        assertThat(AuditoriaService.estado(u)).containsExactly(
                Map.entry("rol", RolUsuario.OPERADOR), Map.entry("companyId", empresa), Map.entry("activo", true));
    }
}
