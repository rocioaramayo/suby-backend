package com.tpo.suby.entity;

import jakarta.persistence.*;
import jakarta.persistence.Lob;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "onboarding_usuarios")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OnboardingUsuario {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer identificador;

    private String nombre;
    private String apellido;
    private String email;
    private String documento;
    private String pais;
    private String direccionLegal;

    @Lob
    @Column(name = "frontal")
    private byte[] frontal;

    @Lob
    @Column(name = "back")
    private byte[] back;

    private String estado;

    private LocalDateTime fechaSolicitud;

    private String motivoRechazo;
}