package com.tpo.suby.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class PrivateMessageService {

    private final JdbcTemplate jdbcTemplate;

    @Transactional
    public Integer createPrivateMessage(Integer recipientId, String type, String subject, String body) {
        return createPrivateMessage(recipientId, type, subject, body, Map.of());
    }

    @Transactional
    public Integer createPrivateMessage(
            Integer recipientId,
            String type,
            String subject,
            String body,
            Map<String, String> data
    ) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO mensajes_privados (
                        destinatario, tipo, asunto, cuerpo
                    )
                    VALUES (?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setInt(1, recipientId);
            ps.setString(2, type);
            ps.setString(3, subject);
            ps.setString(4, body);
            return ps;
        }, keyHolder);

        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("No se pudo crear el mensaje privado.");
        }

        Integer messageId = key.intValue();
        for (Map.Entry<String, String> entry : data.entrySet()) {
            jdbcTemplate.update("""
                    INSERT INTO mensajes_datos (
                        mensaje, clave, valor
                    )
                    VALUES (?, ?, ?)
                    """, messageId, entry.getKey(), entry.getValue());
        }

        return messageId;
    }
}
