package com.tpo.suby.service;

import com.tpo.suby.exception.LotNotFoundException;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AuctionPhotoService {

    private final JdbcTemplate jdbcTemplate;

    public String buildItemPhotoUrl(Integer itemId, Integer photoId) {
        if (itemId == null || itemId <= 0 || photoId == null || photoId <= 0) {
            return null;
        }

        return "/api/v1/auctions/items/%d/photos/%d".formatted(itemId, photoId);
    }

    public List<String> listItemPhotoUrls(Integer itemId) {
        if (itemId == null || itemId <= 0) {
            return List.of();
        }

        List<Integer> photoIds = jdbcTemplate.query("""
                SELECT f.identificador
                FROM itemsCatalogo ic
                JOIN fotos f ON f.producto = ic.producto
                WHERE ic.identificador = ?
                ORDER BY f.identificador ASC
                """, (rs, rowNum) -> rs.getInt("identificador"), itemId);

        return photoIds.stream()
                .map(photoId -> buildItemPhotoUrl(itemId, photoId))
                .toList();
    }

    public AuctionPhotoBinary loadItemPhoto(Integer itemId, Integer photoId) {
        if (itemId == null || itemId <= 0 || photoId == null || photoId <= 0) {
            throw new LotNotFoundException("Foto no encontrada.");
        }

        try {
            byte[] bytes = jdbcTemplate.queryForObject("""
                    SELECT f.foto
                    FROM itemsCatalogo ic
                    JOIN fotos f ON f.producto = ic.producto
                    WHERE ic.identificador = ?
                      AND f.identificador = ?
                    """, byte[].class, itemId, photoId);

            if (bytes == null || bytes.length == 0) {
                throw new LotNotFoundException("Foto no encontrada.");
            }

            return new AuctionPhotoBinary(bytes, detectContentType(bytes));
        } catch (EmptyResultDataAccessException ex) {
            throw new LotNotFoundException("Foto no encontrada.");
        }
    }

    private String detectContentType(byte[] bytes) {
        if (bytes.length >= 8
                && (bytes[0] & 0xFF) == 0x89
                && bytes[1] == 0x50
                && bytes[2] == 0x4E
                && bytes[3] == 0x47) {
            return "image/png";
        }

        if (bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xFF
                && (bytes[1] & 0xFF) == 0xD8
                && (bytes[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }

        if (bytes.length >= 6) {
            String header = new String(bytes, 0, 6);
            if ("GIF87a".equals(header) || "GIF89a".equals(header)) {
                return "image/gif";
            }
        }

        if (bytes.length >= 12) {
            String riff = new String(bytes, 0, 4);
            String webp = new String(bytes, 8, 4);
            if ("RIFF".equals(riff) && "WEBP".equals(webp)) {
                return "image/webp";
            }
        }

        return "application/octet-stream";
    }

    @Getter
    public static class AuctionPhotoBinary {
        private final byte[] bytes;
        private final String contentType;

        public AuctionPhotoBinary(byte[] bytes, String contentType) {
            this.bytes = bytes;
            this.contentType = contentType;
        }
    }
}
