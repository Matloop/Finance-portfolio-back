package com.example.carteira.model.dtos;

import com.example.carteira.model.enums.FixedIncomeIndex;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

// Em src/main/java/com/example/carteira/model/dtos/CreateFixedIncomeDto.java
@Getter
@Setter
public class CreateFixedIncomeDto {
    @NotBlank
    private String name;
    @NotNull @Positive private BigDecimal investedAmount;
    @NotNull private LocalDate investmentDate;
    @NotNull private LocalDate maturityDate;
    @NotNull private FixedIncomeIndex indexType;
    @NotNull
    @Positive
    private BigDecimal contractedRate;
}