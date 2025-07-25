package com.example.carteira.model.assets;

import com.example.carteira.model.Asset;
import com.example.carteira.model.enums.FixedIncomeIndex;
import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@DiscriminatorValue("FIXED_INCOME") // Value to be inserted into the ASSET_TYPE column
public class FixedIncome extends Asset {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FixedIncomeIndex indexType;

    // The contracted rate. E.g., 10.5 (for 10.5% p.a.) or 110 (for 110% of CDI)
    @Column(nullable = false)
    private BigDecimal contractedRate;

    // Getters and Setters
    public FixedIncomeIndex getIndexType() { return indexType; }
    public void setIndexType(FixedIncomeIndex indexType) { this.indexType = indexType; }
    public BigDecimal getContractedRate() { return contractedRate; }
    public void setContractedRate(BigDecimal contractedRate) { this.contractedRate = contractedRate; }
}