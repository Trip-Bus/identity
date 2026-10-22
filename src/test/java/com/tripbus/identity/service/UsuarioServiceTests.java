package com.tripbus.identity.service;

import com.tripbus.identity.client.CatalogClient;
import com.tripbus.identity.dto.CambiarEstadoRequest;
import com.tripbus.identity.dto.CambiarRolRequest;
import com.tripbus.identity.dto.CrearUsuarioRequest;
import com.tripbus.identity.dto.PageResponse;
import com.tripbus.identity.dto.UsuarioResponse;
import com.tripbus.identity.events.IdentityEventPublisher;
import com.tripbus.identity.events.SuspensionPublisher;
import com.tripbus.identity.exception.CatalogNoDisponibleException;
import com.tripbus.identity.exception.CompanyIdRequeridoException;
import com.tripbus.identity.exception.DocumentoYaRegistradoException;
import com.tripbus.identity.exception.EmailYaRegistradoException;
import com.tripbus.identity.exception.EmpresaInactivaException;
import com.tripbus.identity.exception.EmpresaNoEncontradaException;
import com.tripbus.identity.exception.NoPuedeSuspenderseException;
import com.tripbus.identity.exception.RolNoPermitidoException;
import com.tripbus.identity.exception.UsuarioNoEncontradoException;
import com.tripbus.identity.model.RolUsuario;
import com.tripbus.identity.model.Usuario;
import com.tripbus.identity.repository.UsuarioRepository;
import com.tripbus.identity.security.PasswordHasher;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UsuarioServiceTests {

    @Mock
    private UsuarioRepository usuarioRepository;
    @Mock
    private CatalogClient catalogClient;
    @Mock
    private PasswordHasher passwordHasher;
    @Mock
    private PasswordResetService passwordResetService;
    @Mock
    private SesionService sesionService;
    @Mock
    private IdentityEventPublisher events;
    @Mock
    private SuspensionPublisher suspensionPublisher;

    private UsuarioService service;
    private final UUID adminId = UUID.randomUUID();
    private final UUID companyId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new UsuarioService(usuarioRepository, catalogClient, passwordHasher, passwordResetService,
                sesionService, events, suspensionPublisher);
    }

    private static Usuario usuario(boolean activo) {
        Usuario u = new Usuario("ana@tripbus.co", "hash", "CC-1", "Ana");
        u.setActivo(activo);
        return u;
    }

    private CrearUsuarioRequest crearRequest(RolUsuario rol, UUID empresa) {
        return new CrearUsuarioRequest("Ana Pérez", " Ana@TripBus.co ", "CC-77", rol, empresa);
    }

    // --- US-101: cambiar rol ---

    @Test
    void cambiarRolAOperadorValidaLaEmpresaEnCatalogYGuarda() {
        UUID id = UUID.randomUUID();
        Usuario u = usuario(true);
        when(usuarioRepository.findById(id)).thenReturn(Optional.of(u));
        when(usuarioRepository.save(u)).thenReturn(u);

        UsuarioResponse r = service.cambiarRol(id, new CambiarRolRequest(RolUsuario.OPERADOR, companyId));

        verify(catalogClient).validarEmpresaActiva(companyId);
        assertThat(r.rol()).isEqualTo(RolUsuario.OPERADOR);
        assertThat(r.companyId()).isEqualTo(companyId);
    }

    @Test
    void cambiarRolAConductorSinCompanyIdLanzaCompanyIdRequeridoSinLlamarACatalog() {
        UUID id = UUID.randomUUID();
        when(usuarioRepository.findById(id)).thenReturn(Optional.of(usuario(true)));

        assertThatThrownBy(() -> service.cambiarRol(id, new CambiarRolRequest(RolUsuario.CONDUCTOR, null)))
                .isInstanceOf(CompanyIdRequeridoException.class);
        verifyNoInteractions(catalogClient);
        verify(usuarioRepository, never()).save(any());
    }

    @Test
    void siCatalogRechazaLaEmpresaNoSeAsignaElRol() {
        UUID id = UUID.randomUUID();
        when(usuarioRepository.findById(id)).thenReturn(Optional.of(usuario(true)));
        doThrow(new EmpresaInactivaException()).when(catalogClient).validarEmpresaActiva(companyId);

        assertThatThrownBy(() -> service.cambiarRol(id, new CambiarRolRequest(RolUsuario.OPERADOR, companyId)))
                .isInstanceOf(EmpresaInactivaException.class);
        verify(usuarioRepository, never()).save(any());
    }

    @Test
    void cambiarRolAPasajeroNoConsultaCatalogYBorraLaEmpresa() {
        UUID id = UUID.randomUUID();
        Usuario u = usuario(true);
        u.setRol(RolUsuario.OPERADOR);
        u.setCompanyId(companyId);
        when(usuarioRepository.findById(id)).thenReturn(Optional.of(u));
        when(usuarioRepository.save(u)).thenReturn(u);

        UsuarioResponse r = service.cambiarRol(id, new CambiarRolRequest(RolUsuario.PASAJERO, companyId));

        verifyNoInteractions(catalogClient);
        assertThat(r.companyId()).isNull();
    }

    // --- US-102: listar ---

    @Test
    void listarLimitaElTamanoA100YOrdenaPorFechaDeCreacionDescendente() {
        when(usuarioRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenAnswer(inv -> new PageImpl<>(List.of(usuario(true)), inv.getArgument(1), 1));

        PageResponse<UsuarioResponse> r = service.listar(null, null, null, null, -3, 500);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(usuarioRepository).findAll(any(Specification.class), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(100);
        assertThat(captor.getValue().getPageNumber()).isZero();
        assertThat(captor.getValue().getSort().getOrderFor("fechaCreacion").getDirection())
                .isEqualTo(Sort.Direction.DESC);
        assertThat(r.content()).hasSize(1);
        assertThat(r.totalElements()).isEqualTo(1);
    }

    @Test
    void listarConTamanoInvalidoUsaElTamanoPorDefecto() {
        when(usuarioRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenAnswer(inv -> new PageImpl<>(List.of(), inv.getArgument(1), 0));

        PageResponse<UsuarioResponse> r = service.listar(RolUsuario.OPERADOR, companyId, true, "ana", 2, 0);

        assertThat(r.size()).isEqualTo(20);
        assertThat(r.page()).isEqualTo(2);
    }

    // --- US-103: crear ---

    @Test
    void crearOperadorGuardaSinContrasenaConocidaRegistraConsentimientoEnviaEnlaceYPublicaEvento() {
        when(passwordHasher.hash(anyString())).thenReturn("hash-aleatorio");
        when(usuarioRepository.saveAndFlush(any(Usuario.class))).thenAnswer(inv -> inv.getArgument(0));

        UsuarioResponse r = service.crear(crearRequest(RolUsuario.OPERADOR, companyId), adminId);

        assertThat(r.email()).isEqualTo("ana@tripbus.co");
        assertThat(r.rol()).isEqualTo(RolUsuario.OPERADOR);
        assertThat(r.companyId()).isEqualTo(companyId);
        verify(catalogClient).validarEmpresaActiva(companyId);
        verify(usuarioRepository).existsByEmail("ana@tripbus.co");
        verify(sesionService).registrarConsentimientoPorAdmin(r.id(), adminId);
        verify(passwordResetService).enviarEnlaceDefinirContrasena(any(Usuario.class));
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(events).publish(eq(IdentityEventPublisher.USER_REGISTERED), payload.capture());
        assertThat(payload.getValue()).containsEntry("email", "ana@tripbus.co")
                .containsEntry("role", RolUsuario.OPERADOR).containsKeys("userId", "timestamp");
    }

    @Test
    void crearConRolPasajeroOAdminSeRechaza() {
        assertThatThrownBy(() -> service.crear(crearRequest(RolUsuario.PASAJERO, companyId), adminId))
                .isInstanceOf(RolNoPermitidoException.class);
        assertThatThrownBy(() -> service.crear(crearRequest(RolUsuario.ADMIN, companyId), adminId))
                .isInstanceOf(RolNoPermitidoException.class);
        verifyNoInteractions(usuarioRepository, catalogClient);
    }

    @Test
    void crearConEmailRepetidoLanza409DeEmail() {
        when(usuarioRepository.existsByEmail("ana@tripbus.co")).thenReturn(true);

        assertThatThrownBy(() -> service.crear(crearRequest(RolUsuario.CONDUCTOR, companyId), adminId))
                .isInstanceOf(EmailYaRegistradoException.class);
        verifyNoInteractions(catalogClient);
    }

    @Test
    void crearConDocumentoRepetidoLanza409DeDocumento() {
        when(usuarioRepository.existsByDocumentoIdentidad("CC-77")).thenReturn(true);

        assertThatThrownBy(() -> service.crear(crearRequest(RolUsuario.CONDUCTOR, companyId), adminId))
                .isInstanceOf(DocumentoYaRegistradoException.class);
    }

    @Test
    void crearSinCompanyIdLanzaCompanyIdRequerido() {
        assertThatThrownBy(() -> service.crear(crearRequest(RolUsuario.CONDUCTOR, null), adminId))
                .isInstanceOf(CompanyIdRequeridoException.class);
        verify(usuarioRepository, never()).saveAndFlush(any());
    }

    @Test
    void crearConEmpresaInexistenteOCatalogCaidoNoGuardaNada() {
        doThrow(new EmpresaNoEncontradaException()).when(catalogClient).validarEmpresaActiva(companyId);
        assertThatThrownBy(() -> service.crear(crearRequest(RolUsuario.OPERADOR, companyId), adminId))
                .isInstanceOf(EmpresaNoEncontradaException.class);

        doThrow(new CatalogNoDisponibleException()).when(catalogClient).validarEmpresaActiva(companyId);
        assertThatThrownBy(() -> service.crear(crearRequest(RolUsuario.OPERADOR, companyId), adminId))
                .isInstanceOf(CatalogNoDisponibleException.class);

        verify(usuarioRepository, never()).saveAndFlush(any());
        verifyNoInteractions(sesionService, passwordResetService, events);
    }

    @Test
    void crearConcurrenteQueChocaConElIndiceDeEmailSeTraduceA409() {
        when(passwordHasher.hash(anyString())).thenReturn("hash");
        ConstraintViolationException cve = new ConstraintViolationException(
                "duplicado", new SQLException("duplicado"), "uq_usuarios_email_lower");
        when(usuarioRepository.saveAndFlush(any(Usuario.class)))
                .thenThrow(new DataIntegrityViolationException("duplicado", cve));

        assertThatThrownBy(() -> service.crear(crearRequest(RolUsuario.OPERADOR, companyId), adminId))
                .isInstanceOf(EmailYaRegistradoException.class);
    }

    @Test
    void crearConcurrenteQueChocaConElIndiceDeDocumentoSeTraduceA409() {
        when(passwordHasher.hash(anyString())).thenReturn("hash");
        when(usuarioRepository.saveAndFlush(any(Usuario.class))).thenThrow(new DataIntegrityViolationException(
                "x", new SQLException("violates unique constraint uq_usuarios_documento_identidad")));

        assertThatThrownBy(() -> service.crear(crearRequest(RolUsuario.OPERADOR, companyId), adminId))
                .isInstanceOf(DocumentoYaRegistradoException.class);
    }

    // --- US-104: suspender / reactivar ---

    @Test
    void suspenderMarcaInactivoYAvisaPorRedis() {
        UUID id = UUID.randomUUID();
        Usuario u = usuario(true);
        when(usuarioRepository.findById(id)).thenReturn(Optional.of(u));
        when(usuarioRepository.save(u)).thenReturn(u);

        UsuarioResponse r = service.cambiarEstado(id, new CambiarEstadoRequest(false, "Fraude"), adminId);

        assertThat(r.activo()).isFalse();
        verify(suspensionPublisher).publicarSuspension(id, "Fraude", true);
    }

    @Test
    void suspenderUnaCuentaYaSuspendidaNoGuardaPeroReescribeLaClave() {
        UUID id = UUID.randomUUID();
        when(usuarioRepository.findById(id)).thenReturn(Optional.of(usuario(false)));

        service.cambiarEstado(id, new CambiarEstadoRequest(false, null), adminId);

        verify(usuarioRepository, never()).save(any());
        verify(suspensionPublisher).publicarSuspension(id, null, false);
    }

    @Test
    void reactivarMarcaActivoYBorraLaClave() {
        UUID id = UUID.randomUUID();
        Usuario u = usuario(false);
        when(usuarioRepository.findById(id)).thenReturn(Optional.of(u));
        when(usuarioRepository.save(u)).thenReturn(u);

        UsuarioResponse r = service.cambiarEstado(id, new CambiarEstadoRequest(true, "Aclarado"), adminId);

        assertThat(r.activo()).isTrue();
        verify(suspensionPublisher).publicarReactivacion(id, "Aclarado", true);
    }

    @Test
    void unAdminNoPuedeSuspenderseASiMismo() {
        assertThatThrownBy(() -> service.cambiarEstado(adminId, new CambiarEstadoRequest(false, "x"), adminId))
                .isInstanceOf(NoPuedeSuspenderseException.class);
        verifyNoInteractions(usuarioRepository, suspensionPublisher);
    }

    @Test
    void cambiarEstadoDeUsuarioInexistenteLanza404() {
        UUID id = UUID.randomUUID();
        when(usuarioRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cambiarEstado(id, new CambiarEstadoRequest(true, null), adminId))
                .isInstanceOf(UsuarioNoEncontradoException.class);
    }
}
