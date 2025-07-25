// Location: src/main/java/com/yourportfolio/service/FixedIncomeCalculationService.java
package com.example.carteira.service;


import com.example.carteira.model.assets.FixedIncome;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

@Service
public class FixedIncomeCalculationService {

    // Simulated index values. In a real project, you would fetch these
    // from a financial data source like a Central Bank API.
    private static final BigDecimal ANNUAL_CDI_RATE = new BigDecimal("0.10"); // 10% p.a.
    private static final BigDecimal ANNUAL_IPCA_RATE = new BigDecimal("0.05"); // 5% p.a.

    public BigDecimal calculateCurrentValue(FixedIncome asset) {
        long elapsedDays = ChronoUnit.DAYS.between(asset.getInvestmentDate(), LocalDate.now());
        if (elapsedDays <= 0) {
            return asset.getInvestedAmount();
        }

        BigDecimal dailyRate = BigDecimal.ZERO;
        BigDecimal investedAmount = asset.getInvestedAmount();

        switch (asset.getIndexType()) {
            case PRE_FIXED:
                // Simple interest for simplicity: (Annual Rate / 365) * days
                dailyRate = asset.getContractedRate().divide(new BigDecimal("100"), 10, RoundingMode.HALF_UP)
                        .divide(new BigDecimal("365"), 10, RoundingMode.HALF_UP);
                break;
            case CDI:
                // (Annual CDI * (%CDI contracted / 100)) / 365
                dailyRate = ANNUAL_CDI_RATE.multiply(asset.getContractedRate().divide(new BigDecimal("100"), 10, RoundingMode.HALF_UP))
                        .divide(new BigDecimal("365"), 10, RoundingMode.HALF_UP);
                break;
            case IPCA:
                // (Annual IPCA + Contracted Rate) / 365
                dailyRate = ANNUAL_IPCA_RATE.add(asset.getContractedRate().divide(new BigDecimal("100"), 10, RoundingMode.HALF_UP))
                        .divide(new BigDecimal("365"), 10, RoundingMode.HALF_UP);
                break;
        }

        BigDecimal profit = investedAmount.multiply(dailyRate).multiply(new BigDecimal(elapsedDays));
        return investedAmount.add(profit).setScale(2, RoundingMode.HALF_UP);
    }
}