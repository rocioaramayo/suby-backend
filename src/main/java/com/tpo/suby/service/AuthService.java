package com.tpo.suby.service;

import com.tpo.suby.dto.request.ForgotPasswordRequest;
import com.tpo.suby.dto.request.LoginRequest;
import com.tpo.suby.dto.request.OnboardingRequest;
import com.tpo.suby.dto.request.ResetPasswordRequest;
import com.tpo.suby.dto.request.VerifyCodeRequest;
import com.tpo.suby.dto.response.ApiResponse;
import com.tpo.suby.config.JwtService;
import com.tpo.suby.entity.OnboardingUsuario;
import com.tpo.suby.entity.Persona;
import com.tpo.suby.entity.RevokedToken;
import com.tpo.suby.entity.UsuarioApp;
import com.tpo.suby.exception.CodeExpiredException;
import com.tpo.suby.exception.InvalidTokenException;
import com.tpo.suby.exception.NotFoundException;
import com.tpo.suby.repository.OnboardingUsuarioRepository;
import com.tpo.suby.repository.PersonaRepository;
import com.tpo.suby.repository.RevokedTokenRepository;
import com.tpo.suby.repository.UsuarioAppRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.Random;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UsuarioAppRepository usuarioRepository;
    private final OnboardingUsuarioRepository onboardingUsuarioRepository;
    private final PersonaRepository personaRepository;
    private final RevokedTokenRepository revokedTokenRepository;
    private final AuthenticationManager authenticationManager;
    private final PasswordEncoder passwordEncoder;
    private final JavaMailSender mailSender;
    private final JdbcTemplate jdbcTemplate;

    private final JwtService jwtService;

    public Map<String, Object> logout(String token) {
        if (token == null || token.isBlank()) {
            throw new InvalidTokenException("Token inválido o expirado.");
        }

        try {
            if (jwtService.extractExpiration(token).before(new java.util.Date())) {
                throw new InvalidTokenException("Token inválido o expirado.");
            }
        } catch (Exception e) {
            throw new InvalidTokenException("Token inválido o expirado.");
        }

        String tokenHash = jwtService.hashToken(token);
        if (!revokedTokenRepository.existsByTokenHash(tokenHash)) {
            RevokedToken revokedToken = RevokedToken.builder()
                    .tokenHash(tokenHash)
                    .expiresAt(LocalDateTime.ofInstant(
                            jwtService.extractExpiration(token).toInstant(),
                            java.time.ZoneId.systemDefault()
                    ))
                    .build();

            revokedTokenRepository.save(revokedToken);
        }

        return Map.of(
                "status", "success",
                "message", "Sesión cerrada exitosamente."
        );
    }

        public Map<String, Object> forgotPassword(ForgotPasswordRequest request) {
        UsuarioApp usuario = usuarioRepository
            .findByEmail(request.getEmail())
            .orElseThrow(() -> new NotFoundException(
                "No existe ninguna cuenta asociada a este correo electrónico."
            ));

        String code = String.format("%06d", new Random().nextInt(1_000_000));

        usuario.setTokenRecuperacion(code);
        usuario.setTokenExpira(LocalDateTime.now().plusMinutes(15));
        usuarioRepository.save(usuario);

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom("rocioaramay@gmail.com");
        message.setTo(usuario.getEmail());
        message.setSubject("Código de recuperación - Suby");
        message.setText("""
    Tu código de recuperación es:

    %s

    El código vence en 15 minutos.
    """.formatted(code));

        try {
            mailSender.send(message);
        } catch (Exception e) {
            throw new RuntimeException(
                    "Ocurrió un problema al intentar enviar el correo. Por favor, inténtalo más tarde."
            );
        }

        return Map.of(
            "status", "success",
            "message", "Hemos enviado un código de 6 dígitos para recuperar tu cuenta."
        );
        }

        public Map<String, Object> verifyCode(VerifyCodeRequest request) {
        UsuarioApp usuario = usuarioRepository
            .findByEmail(request.getEmail())
            .orElseThrow(() -> new NotFoundException(
                "No se encontró una solicitud de recuperación de contraseña para este correo."
            ));

        validateRecoveryCode(usuario, request.getCode());

        return Map.of(
            "status", "success",
            "message", Map.of(
                "text", "El código ha sido verificado correctamente."
            )
        );
        }

    @Transactional
    public Map<String, Object> resetPassword(ResetPasswordRequest request) {
        UsuarioApp usuario = usuarioRepository
                .findByEmail(request.getEmail())
                .orElseThrow(() -> new NotFoundException(
                        "No se encontró una solicitud de recuperación de contraseña para este correo."
                ));

        validateRecoveryCode(usuario, request.getCode());
        validateNewPassword(request.getPassword(), request.getPasswordConfirmation());

        usuario.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        usuario.setUltimoLogin(LocalDateTime.now());
        usuario.setIntentosFallidos(0);
        usuario.setBloqueadoHasta(null);
        usuario.setTokenRecuperacion(null);
        usuario.setTokenExpira(null);
        usuarioRepository.save(usuario);

        return Map.of(
                "status", "success",
                "message", "La contrasena ha sido configurada exitosamente."
        );
    }

    public ApiResponse<Map<String, Object>> login(LoginRequest request) {

        UsuarioApp usuario = usuarioRepository
                .findByEmail(request.getEmail())
                .orElseThrow(() ->
                        new RuntimeException("Usuario no encontrado")
                );

        // Verificar si la cuenta está bloqueada temporalmente
        if (usuario.getBloqueadoHasta() != null &&
                usuario.getBloqueadoHasta().isAfter(LocalDateTime.now())) {
            throw new LockedException("Cuenta bloqueada");
        }

        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(
                            request.getEmail(),
                            request.getPassword()
                    )
            );
        } catch (BadCredentialsException e) {
            // Incrementar intentos fallidos
            Integer intentos = usuario.getIntentosFallidos() != null ?
                    usuario.getIntentosFallidos() : 0;
            intentos++;
            usuario.setIntentosFallidos(intentos);

                // Si llega a 5 intentos, bloquear por 15 minutos
            if (intentos >= 5) {
                usuario.setBloqueadoHasta(
                        LocalDateTime.now().plusMinutes(15)
                );
            }

            usuarioRepository.save(usuario);
            throw e;
        }

        // Login exitoso: reiniciar intentos fallidos
        usuario.setIntentosFallidos(0);
        usuario.setBloqueadoHasta(null);

        boolean firstLogin = usuario.getUltimoLogin() == null;
        String token = jwtService.generateToken(usuario);
        usuario.setUltimoLogin(LocalDateTime.now());
        usuarioRepository.save(usuario);

        Map<String, Object> user = new HashMap<>();
        user.put("id", usuario.getIdentificador());
        user.put("email", usuario.getEmail());
        user.put("name", usuario.getPersona().getNombre());

        Map<String, Object> message = new HashMap<>();
        message.put("user", user);
        message.put("token", token);
        message.put("first_login", firstLogin);

        return ApiResponse.<Map<String, Object>>builder()
                .status("success")
                .message(message)
                .build();
    }

    private void validateRecoveryCode(UsuarioApp usuario, String code) {
        if (usuario.getTokenRecuperacion() == null || usuario.getTokenExpira() == null) {
            throw new NotFoundException(
                    "No se encontró una solicitud de recuperación de contraseña para este correo."
            );
        }

        if (usuario.getTokenExpira().isBefore(LocalDateTime.now())) {
            throw new CodeExpiredException("El código de verificación ha expirado.");
        }

        if (!usuario.getTokenRecuperacion().equals(code)) {
            throw new BadCredentialsException("El código ingresado es incorrecto.");
        }
    }

    private void validateNewPassword(String password, String passwordConfirmation) {
        if (password == null
                || passwordConfirmation == null
                || !password.equals(passwordConfirmation)
                || password.length() < 8) {
            throw new RuntimeException(
                    "Errores de validacion: Las contrasenas no coinciden o no alcanzan el minimo de 8 caracteres."
            );
        }
    }

    public void onboarding(OnboardingRequest request) {

        if (request.getEmail() != null && usuarioRepository.existsByEmail(request.getEmail().trim())) {
            throw new IllegalStateException("La cuenta ya fue aprobada.");
        }

        if (
                request.getName() == null ||
                request.getSurname() == null ||
                request.getEmail() == null ||
                request.getCountry() == null ||
                request.getLegalAddress() == null ||
                request.getFrontal() == null ||
                request.getBack() == null
        ) {
            throw new RuntimeException("Bad request");
        }

        if (request.getFrontal().isEmpty() || request.getBack().isEmpty()) {
            throw new IllegalArgumentException("errores de validacion");
        }

        String email = request.getEmail().trim();

        Optional<OnboardingUsuario> existente = onboardingUsuarioRepository.findFirstByEmailIgnoreCase(email);

        if (existente.isPresent()) {
            String estado = existente.get().getEstado();

            if (estado != null && estado.equalsIgnoreCase("pendiente")) {
                throw new IllegalStateException("Ya existe una solicitud pendiente para este email.");
            }

            if (estado != null && estado.equalsIgnoreCase("suspendido")) {
                throw new IllegalStateException("La cuenta está suspendida.");
            }

            if (estado != null && (
                estado.equalsIgnoreCase("aprobado") ||
                estado.equalsIgnoreCase("procesado")
            )) {
                throw new IllegalStateException("La cuenta ya fue aprobada.");
            }

            throw new IllegalStateException("Ya existe una solicitud registrada para este email.");
        }

        try {
            OnboardingUsuario onboarding = OnboardingUsuario.builder()
                .nombre(request.getName())
                .apellido(request.getSurname())
                .email(email)
                .documento(request.getDocument())
                .pais(request.getCountry())
                .direccionLegal(request.getLegalAddress())
                .frontal(request.getFrontal().getBytes())
                .back(request.getBack().getBytes())
                .estado("pendiente")
                .fechaSolicitud(LocalDateTime.now())
                .build();

            onboardingUsuarioRepository.save(onboarding);

                SimpleMailMessage message = new SimpleMailMessage();
                message.setFrom("rocioaramay@gmail.com");
                message.setTo(email);
                message.setSubject("Solicitud recibida - Suby");
                message.setText("""
Hola %s,

Recibimos correctamente tu solicitud de registro en Suby.

Nuestro equipo revisará la documentación enviada dentro de las próximas 24-72 hs.

Saludos,
Equipo Suby
""".formatted(request.getName()));

                mailSender.send(message);
        } catch (IOException e) {
            throw new RuntimeException("Bad request", e);
        }
    }

        // Run every 5 minutes (300000 ms) to avoid spamming emails
        @Scheduled(fixedRate = 10000)
        @Transactional
            public void processApprovedAccounts() {

        List<OnboardingUsuario> aprobados =
            onboardingUsuarioRepository.findByEstado("aprobado");

        for (OnboardingUsuario onboarding : aprobados) {
            try {
                Optional<UsuarioApp> usuarioExistente = usuarioRepository.findByEmail(onboarding.getEmail());

                Persona persona;
                String tempPassword = null;

                if (usuarioExistente.isPresent()) {
                    persona = usuarioExistente.get().getPersona();
                } else {
                    tempPassword = UUID.randomUUID()
                        .toString()
                        .substring(0, 8);

                    persona = Persona.builder()
                        .nombre(onboarding.getNombre())
                        .documento(onboarding.getDocumento())
                        .direccion(onboarding.getDireccionLegal())
                        .estado("activo")
                        .build();

                    persona = personaRepository.saveAndFlush(persona);

                    UsuarioApp usuario = UsuarioApp.builder()
                        .persona(persona)
                        .email(onboarding.getEmail())
                        .passwordHash(
                            passwordEncoder.encode(tempPassword)
                        )
                        .estadoApp("activo")
                        .ultimoLogin(null)
                        .intentosFallidos(0)
                        .bloqueadoHasta(null)
                        .build();

                    usuarioRepository.saveAndFlush(usuario);
                }

                createClientProfile(persona.getIdentificador(), onboarding.getPais());

                // Mark onboarding as processed before sending email to avoid duplicates.
                onboarding.setEstado("procesado");
                onboardingUsuarioRepository.save(onboarding);

                if (tempPassword == null) {
                    continue;
                }

                SimpleMailMessage message = new SimpleMailMessage();
                message.setFrom("rocioaramay@gmail.com");
                message.setTo(onboarding.getEmail());
                message.setSubject("Cuenta aprobada - Suby");
                message.setText("""
Hola %s,

Tu solicitud de registro fue aprobada correctamente.

Ya podés ingresar a Suby utilizando las siguientes credenciales temporales:

Email: %s
Contraseña temporal: %s

Por seguridad, al iniciar sesión por primera vez se te solicitará cambiar la contraseña.

Saludos,
Equipo Suby
""".formatted(
                    onboarding.getNombre(),
                    onboarding.getEmail(),
                    tempPassword
                ));

                try {
                    mailSender.send(message);
                } catch (Exception e) {
                    // Log and continue; onboarding already marked processed
                    log.warn("Failed to send approval email to {}", onboarding.getEmail(), e);
                }
            } catch (Exception e) {
                log.error("Failed to process approved onboarding for {}", onboarding.getEmail(), e);
            }
        }
    }

    private void createClientProfile(Integer personId, String country) {
        if (clientExists(personId)) {
            return;
        }

        Integer countryId = resolveCountryId(country);

        jdbcTemplate.update("""
                INSERT INTO clientes (
                    identificador, numeroPais, admitido, categoria, verificador
                )
                VALUES (?, ?, ?, ?, ?)
                """, personId, countryId, "si", "comun", firstEmployeeId());
    }

    private boolean clientExists(Integer personId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM clientes WHERE identificador = ?",
                Integer.class,
                personId
        );
        return count != null && count > 0;
    }

    private Integer firstEmployeeId() {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT TOP 1 identificador FROM empleados ORDER BY identificador ASC",
                    Integer.class
            );
        } catch (EmptyResultDataAccessException ex) {
            throw new IllegalStateException("No existe un empleado verificador para crear el cliente.");
        }
    }

    private Integer resolveCountryId(String country) {
        String normalizedCountry = normalize(country);

        try {
            return jdbcTemplate.queryForObject("""
                    SELECT TOP 1 numero
                    FROM paises
                    WHERE LOWER(nombre) = ? OR LOWER(nombreCorto) = ?
                    ORDER BY numero ASC
                    """,
                    Integer.class,
                    normalizedCountry,
                    normalizedCountry
            );
        } catch (EmptyResultDataAccessException ex) {
            try {
                return jdbcTemplate.queryForObject("""
                        SELECT TOP 1 numero
                        FROM paises
                        WHERE LOWER(nombre) LIKE ?
                           OR LOWER(nombreCorto) LIKE ?
                        ORDER BY numero ASC
                        """,
                        Integer.class,
                        "%" + normalizedCountry + "%",
                        "%" + normalizedCountry + "%"
                );
            } catch (EmptyResultDataAccessException ignored) {
                throw new IllegalStateException("No se encontró el país de la solicitud.");
            }
        }
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }

        String normalized = Normalizer.normalize(value.trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");

        return normalized.toLowerCase();
    }
}
