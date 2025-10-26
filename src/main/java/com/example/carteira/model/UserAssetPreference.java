
package com.example.carteira.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
@Table(uniqueConstraints = { // Garante que não haja duplicatas
        @UniqueConstraint(columnNames = {"user_id", "assetIdentifier"})
})
public class UserAssetPreference {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    // Identificador único do ativo: "BTC-USD", "Meu CDB 2025", "PETR4"
    @Column(nullable = false)
    private String assetIdentifier;

    @Column(nullable = false)
    private boolean isTreatedAsCash;
}