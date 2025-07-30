
package com.example.carteira.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "ASSET_TYPE") // Column that identifies the asset type
@Getter
@Setter
public abstract class Asset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name; // E.g., "Bitcoin", "Treasury Selic 2029"

    @Column(nullable = false)
    private BigDecimal investedAmount;

    @Column(nullable = false)
    private LocalDate investmentDate;


}