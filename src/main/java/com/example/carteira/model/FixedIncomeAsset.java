package com.example.carteira.model;

import com.example.carteira.model.enums.FixedIncomeIndex;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Getter
@Setter
public class FixedIncomeAsset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal investedAmount;

    @Column(nullable = false)
    private LocalDate investmentDate;
    @Column(nullable = false)
    private boolean isDailyLiquid;

    @Column(nullable = false)
    private LocalDate maturityDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FixedIncomeIndex indexType;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal contractedRate;
}