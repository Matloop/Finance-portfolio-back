package com.example.carteira.service.util;

import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

@Service
public class IncomeTaxService {

    /**
     * Calcula a alíquota do Imposto de Renda para Renda Fixa com base no prazo.
     * @param investmentDate A data do investimento.
     * @param currentDate A data para a qual o imposto está sendo calculado.
     * @return A alíquota como um BigDecimal (ex: 0.225 para 22.5%).
     */
    public BigDecimal getFixedIncomeTaxRate(LocalDate investmentDate, LocalDate currentDate) {
        long days = ChronoUnit.DAYS.between(investmentDate, currentDate);

        if (days <= 180) {
            return new BigDecimal("0.225"); // 22.5%
        } else if (days <= 360) {
            return new BigDecimal("0.200"); // 20.0%
        } else if (days <= 720) {
            return new BigDecimal("0.175"); // 17.5%
        } else {
            return new BigDecimal("0.150"); // 15.0%
        }
    }
}