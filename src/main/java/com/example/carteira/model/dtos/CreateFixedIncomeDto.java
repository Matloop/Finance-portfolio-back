package com.example.carteira.model.dtos;

import com.example.carteira.model.enums.FixedIncomeIndex;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@Setter
public class CreateFixedIncomeDto {

    @NotBlank(message = "O nome do ativo não pode ser vazio.")
    private String name;

    @NotNull(message = "O valor investido não pode ser nulo.")
    private BigDecimal investedAmount;

    @NotNull(message = "A data de investimento não pode ser nula.")
    @PastOrPresent(message = "A data de investimento não pode ser no futuro.")
    private LocalDate investmentDate;

    private boolean isDailyLiquid;

    @NotNull(message = "A data de vencimento não pode ser nula.")
    @Future(message = "A data de vencimento deve ser no futuro.")
    private LocalDate maturityDate;

    @NotNull(message = "O tipo de indexador não pode ser nulo.")
    private FixedIncomeIndex indexType;

    private BigDecimal contractedRate;
}