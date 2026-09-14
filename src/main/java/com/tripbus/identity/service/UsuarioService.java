package com.tripbus.identity.service;

import com.tripbus.identity.dto.CambiarRolRequest;
import com.tripbus.identity.dto.UsuarioResponse;
import com.tripbus.identity.exception.CompanyIdRequeridoException;
import com.tripbus.identity.exception.UsuarioNoEncontradoException;
import com.tripbus.identity.model.RolUsuario;
import com.tripbus.identity.model.Usuario;
import com.tripbus.identity.repository.UsuarioRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class UsuarioService {

    private final UsuarioRepository usuarioRepository;

    public UsuarioService(UsuarioRepository usuarioRepository) {
        this.usuarioRepository = usuarioRepository;
    }

    @Transactional(readOnly = true)
    public UsuarioResponse obtenerPorId(UUID id) {
        Usuario usuario = usuarioRepository.findById(id).orElseThrow(UsuarioNoEncontradoException::new);
        return toResponse(usuario);
    }

    @Transactional
    public UsuarioResponse cambiarRol(UUID id, CambiarRolRequest request) {
        Usuario usuario = usuarioRepository.findById(id).orElseThrow(UsuarioNoEncontradoException::new);

        RolUsuario nuevoRol = request.rol();
        boolean requiereCompanyId = nuevoRol == RolUsuario.OPERADOR || nuevoRol == RolUsuario.CONDUCTOR;

        if (requiereCompanyId && request.companyId() == null) {
            throw new CompanyIdRequeridoException();
        }

        usuario.setRol(nuevoRol);
        usuario.setCompanyId(requiereCompanyId ? request.companyId() : null);
        usuario = usuarioRepository.save(usuario);

        return toResponse(usuario);
    }

    private UsuarioResponse toResponse(Usuario usuario) {
        return new UsuarioResponse(
                usuario.getId(),
                usuario.getEmail(),
                usuario.getNombre(),
                usuario.getRol(),
                usuario.getCompanyId(),
                usuario.isActivo());
    }
}
