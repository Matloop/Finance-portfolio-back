// Location: src/main/java/com/yourportfolio/model/CryptoAsset.java
package com.example.carteira.model.assets;

import com.example.carteira.model.Asset;
import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Entity
@DiscriminatorValue("CRYPTO")// Value to be inserted into the ASSET_TYPE column
@Getter
@Setter
public class Crypto extends Asset {

    @Column(nullable = true)
    private String ticker; // E.g., "BTC", "ETH"

    @Column(nullable = true)
    private BigDecimal quantity;


}