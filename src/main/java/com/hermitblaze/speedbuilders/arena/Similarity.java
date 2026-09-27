package com.hermitblaze.speedbuilders.arena;

/**
 * Resultado de comparar una plataforma con la construcción objetivo.
 *
 * @param percent porcentaje de similitud (0-100)
 * @param perfect {@code true} si la réplica es exacta
 */
public record Similarity(double percent, boolean perfect) {
}
