package com.tpo.suby.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "personas")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Persona {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer identificador;

    private String documento;

    private String nombre;

    private String direccion;

    private String estado;
}