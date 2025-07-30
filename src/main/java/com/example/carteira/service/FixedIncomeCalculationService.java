package com.example.carteira.service;

import com.example.carteira.model.assets.FixedIncome;
import com.example.carteira.service.util.BusinessDayService;
import com.example.carteira.service.util.IncomeTaxService; // Importar
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Map;

@Service
public class FixedIncomeCalculationService {

    private final FinancialIndexService indexService;
    private final BusinessDayService businessDayService;
    private final IncomeTaxService incomeTaxService; // 1. Injetar o serviço de IR

    // DTO interno para retornar todos os valores calculados
    public record CalculationResult(BigDecimal grossValue, BigDecimal incomeTax, BigDecimal netValue) {}

    public FixedIncomeCalculationService(FinancialIndexService indexService,
                                         BusinessDayService businessDayService,
                                         IncomeTaxService incomeTaxService) { // 2. Adicionar ao construtor
        this.indexService = indexService;
        this.businessDayService = businessDayService;
        this.incomeTaxService = incomeTaxService;
    }

    /**
     * Calcula os valores bruto, imposto e líquido para um ativo de renda fixa.
     */
    public CalculationResult calculateValues(FixedIncome asset) {
        LocalDate startDate = asset.getInvestmentDate();
        LocalDate endDate = LocalDate.now();
        BigDecimal investedAmount = asset.getInvestedAmount();

        if (startDate.isAfter(endDate) || startDate.isEqual(endDate)) {
            return new CalculationResult(investedAmount, BigDecimal.ZERO, investedAmount);
        }

        BigDecimal grossValue;
        switch (asset.getIndexType()) {
            case PRE_FIXED:
                grossValue = calculatePrefixedGrossValue(asset, startDate, endDate);
                break;
            case CDI:
                grossValue = calculateCdiGrossValue(asset, startDate, endDate);
                break;
            default: // Inclui IPCA não implementado
                grossValue = investedAmount;
        }

        // 3. Calcular imposto e valor líquido
        BigDecimal grossProfit = grossValue.subtract(investedAmount);
        if (grossProfit.compareTo(BigDecimal.ZERO) <= 0) {
            // Se não houver lucro, não há imposto.
            return new CalculationResult(grossValue, BigDecimal.ZERO, grossValue);
        }

        BigDecimal taxRate = incomeTaxService.getFixedIncomeTaxRate(startDate, endDate);
        BigDecimal taxAmount = grossProfit.multiply(taxRate).setScale(2, RoundingMode.HALF_UP);
        BigDecimal netValue = grossValue.subtract(taxAmount);

        return new CalculationResult(grossValue, taxAmount, netValue);
    }

    // Renomeado para clareza
    private BigDecimal calculatePrefixedGrossValue(FixedIncome asset, LocalDate startDate, LocalDate endDate) {
        long days = businessDayService.countBusinessDays(startDate, endDate);
        // A taxa anualizada DI (252 dias) é o padrão de mercado para Renda Fixa
        double annualRate = asset.getContractedRate().divide(new BigDecimal("100"), 10, RoundingMode.HALF_UP).doubleValue();
        double dailyFactor = Math.pow(1 + annualRate, 1.0 / 252.0);
        double finalAmount = asset.getInvestedAmount().doubleValue() * Math.pow(dailyFactor, days);
        return BigDecimal.valueOf(finalAmount);
    }

    // Renomeado para clareza
    private BigDecimal calculateCdiGrossValue(FixedIncome asset, LocalDate startDate, LocalDate endDate) {
        Map<LocalDate, BigDecimal> cdiRates = indexService.getCdiRatesForPeriod(startDate, endDate);
        if (cdiRates.isEmpty()) {
            System.err.println("Não foi possível obter as taxas CDI. Retornando valor investido.");
            return asset.getInvestedAmount();
        }

        BigDecimal accumulatedFactor = BigDecimal.ONE;
        BigDecimal contractedRatePercentage = asset.getContractedRate().divide(new BigDecimal("100"), 10, RoundingMode.HALF_UP);

        // A fórmula de capitalização diária do CDI
        // Fator Acumulado = (1 + CDI_dia1 * %Contratada) * (1 + CDI_dia2 * %Contratada) * ...
        for (LocalDate date = startDate; date.isBefore(endDate); date = date.plusDays(1)) {
            BigDecimal dailyCdi = cdiRates.get(date); // Pega a taxa do mapa (pode ser nula em fins de semana)
            if (dailyCdi != null) {
                // A taxa diária do CDI já vem na base correta (ex: 0.000384)
                BigDecimal dailyFactor = BigDecimal.ONE.add(dailyCdi.multiply(contractedRatePercentage));
                accumulatedFactor = accumulatedFactor.multiply(dailyFactor);
            }
        }
        return asset.getInvestedAmount().multiply(accumulatedFactor);
    }
}