package com.example.carteira.model.dtos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class CreateCryptoDto {

    @NotBlank(message = "O ticker não pode ser vazio")
    private String ticker;

    @NotNull(message = "A quantidade não pode ser nula")
    @Positive(message = "A quantidade deve ser um número positivo")
    private BigDecimal quantity;

    @NotNull(message = "O valor investido não pode ser nulo")
    @Positive(message = "O valor investido deve ser positivo")
    private BigDecimal investedAmount;

    // O nome é opcional, se não for fornecido podemos usar o ticker.
    private String name;
}