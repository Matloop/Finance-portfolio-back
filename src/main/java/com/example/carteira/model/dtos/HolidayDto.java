package com.example.carteira.model.dtos;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

// Usamos record para uma classe de dados imutável e concisa.
// @JsonIgnoreProperties para ignorar campos extras que a API possa retornar (como "type").
@JsonIgnoreProperties(ignoreUnknown = true)
public record HolidayDto(
        String date, // A data virá no formato "AAAA-MM-DD"
        String name
) {}