package com.tpo.suby.service;

import com.tpo.suby.dto.request.ForgotPasswordRequest;
import com.tpo.suby.dto.request.LoginRequest;
import com.tpo.suby.dto.request.OnboardingRequest;
import com.tpo.suby.dto.request.VerifyCodeRequest;
import com.tpo.suby.dto.response.ApiResponse;
import com.tpo.suby.config.JwtService;
import com.tpo.suby.entity.OnboardingUsuario;
import com.tpo.suby.entity.Persona;
import com.tpo.suby.entity.UsuarioApp;
import com.tpo.suby.exception.CodeExpiredException;
import com.tpo.suby.exception.NotFoundException;
import com.tpo.suby.repository.OnboardingUsuarioRepository;
import com.tpo.suby.repository.PersonaRepository;
import com.tpo.suby.repository.UsuarioAppRepository;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import lombok.RequiredArgsConstructor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
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

    private final UsuarioAppRepository usuarioRepository;
    private final OnboardingUsuarioRepository onboardingUsuarioRepository;
    private final PersonaRepository personaRepository;
    private final AuthenticationManager authenticationManager;
    private final PasswordEncoder passwordEncoder;
    private final JavaMailSender mailSender;

    private final JwtService jwtService;

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

            if (usuario.getTokenRecuperacion() == null || usuario.getTokenExpira() == null) {
                throw new NotFoundException(
                    "No se encontró una solicitud de recuperación de contraseña para este correo."
                );
            }

        if (usuario.getTokenExpira() == null || usuario.getTokenExpira().isBefore(LocalDateTime.now())) {
            throw new CodeExpiredException("El código de verificación ha expirado.");
        }

        if (usuario.getTokenRecuperacion() == null || !usuario.getTokenRecuperacion().equals(request.getCode())) {
            throw new org.springframework.security.authentication.BadCredentialsException(
                "El código ingresado es incorrecto."
            );
        }

        return Map.of(
            "status", "success",
            "message", Map.of(
                "text", "El código ha sido verificado correctamente."
            )
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

    public void onboarding(OnboardingRequest request) {

        Optional<OnboardingUsuario> existente = onboardingUsuarioRepository.findByEmail(request.getEmail());

        if (existente.isPresent()) {
            String estado = existente.get().getEstado();

            if (estado != null && estado.equalsIgnoreCase("pendiente")) {
                throw new IllegalStateException("Ya existe una solicitud pendiente para este email.");
            }

            if (estado != null && estado.equalsIgnoreCase("suspendido")) {
                throw new IllegalStateException("La cuenta está suspendida.");
            }

            if (estado != null && estado.equalsIgnoreCase("aprobado")) {
                throw new IllegalStateException("La cuenta ya fue aprobada.");
            }
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

        try {
            OnboardingUsuario onboarding = OnboardingUsuario.builder()
                .nombre(request.getName())
                .apellido(request.getSurname())
                .email(request.getEmail())
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
                message.setTo(request.getEmail());
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

            boolean existe =
                usuarioRepository.existsByEmail(
                    onboarding.getEmail()
                );

            if (existe) {
            continue;
            }

            String tempPassword = UUID.randomUUID()
                .toString()
                .substring(0, 8);

            Persona persona = Persona.builder()
                .nombre(onboarding.getNombre())
                .documento(onboarding.getDocumento())
                .direccion(onboarding.getDireccionLegal())
                .estado("activo")
                .build();

            persona = personaRepository.save(persona);

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

            usuarioRepository.save(usuario);

            // Mark onboarding as processed before sending email to avoid duplicates
            onboarding.setEstado("procesado");
            onboardingUsuarioRepository.save(onboarding);

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
                System.err.println("Failed to send approval email to " + onboarding.getEmail() + ": " + e.getMessage());
            }
        }
        }
}