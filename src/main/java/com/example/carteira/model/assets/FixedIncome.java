package com.example.carteira.model.assets;

import com.example.carteira.model.Asset;
import com.example.carteira.model.enums.FixedIncomeIndex;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@DiscriminatorValue("FIXED_INCOME")
@Getter
@Setter
public class FixedIncome extends Asset {

    @Enumerated(EnumType.STRING)
    @Column(nullable = true)
    private FixedIncomeIndex indexType;

    @Column(nullable = true)
    private BigDecimal contractedRate;

    // NOVO CAMPO: Essencial para cálculos de IR e para saber quando o título vence.
    @Column(nullable = true) // Pode ser nulo para títulos sem vencimento (raro, mas possível).
    private LocalDate maturityDate;
}