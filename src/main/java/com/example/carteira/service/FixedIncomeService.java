package com.example.carteira.service;

import com.example.carteira.model.FixedIncomeAsset;
import com.example.carteira.model.dtos.AssetPositionDto;
import com.example.carteira.model.dtos.CreateFixedIncomeDto;
import com.example.carteira.repository.FixedIncomeRepository;
import com.example.carteira.service.util.BusinessDayService;
import com.example.carteira.service.util.IncomeTaxService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class FixedIncomeService {

    private final FixedIncomeRepository fixedIncomeRepository;
    private final FinancialIndexService indexService;
    private final IncomeTaxService incomeTaxService;
    private final BusinessDayService businessDayService;

    public FixedIncomeService(FixedIncomeRepository fixedIncomeRepository, FinancialIndexService indexService, IncomeTaxService incomeTaxService, BusinessDayService businessDayService) {
        this.fixedIncomeRepository = fixedIncomeRepository;
        this.indexService = indexService;
        this.incomeTaxService = incomeTaxService;
        this.businessDayService = businessDayService;
    }

    public List<AssetPositionDto> getAllFixedIncomePositions() {
        return fixedIncomeRepository.findAll().stream()
                .map(this::calculatePosition)
                .collect(Collectors.toList());
    }

    public FixedIncomeAsset addFixedIncome(CreateFixedIncomeDto dto) {
        FixedIncomeAsset asset = new FixedIncomeAsset();
        asset.setName(dto.getName());
        asset.setInvestedAmount(dto.getInvestedAmount());
        asset.setInvestmentDate(dto.getInvestmentDate());
        asset.setDailyLiquid(dto.isDailyLiquid());
        asset.setMaturityDate(dto.getMaturityDate());
        asset.setIndexType(dto.getIndexType());
        asset.setContractedRate(dto.getContractedRate());
        return fixedIncomeRepository.save(asset);
    }

    public void deleteFixedIncome(Long id) {
        fixedIncomeRepository.deleteById(id);
    }

    private AssetPositionDto calculatePosition(FixedIncomeAsset asset) {
        // Esta linha agora funciona porque a assinatura de calculateGrossValue foi corrigida.
        BigDecimal grossValue = calculateGrossValue(asset);
        BigDecimal grossProfit = grossValue.subtract(asset.getInvestedAmount());

        BigDecimal taxAmount = BigDecimal.ZERO;
        if (grossProfit.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal taxRate = incomeTaxService.getFixedIncomeTaxRate(asset.getInvestmentDate(), LocalDate.now());
            taxAmount = grossProfit.multiply(taxRate).setScale(2, RoundingMode.HALF_UP);
        }

        BigDecimal netValue = grossValue.subtract(taxAmount);
        BigDecimal netProfit = netValue.subtract(asset.getInvestedAmount());
        BigDecimal profitability = asset.getInvestedAmount().compareTo(BigDecimal.ZERO) == 0 ?
                BigDecimal.ZERO :
                netProfit.divide(asset.getInvestedAmount(), 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));

        AssetPositionDto dto = new AssetPositionDto();
        dto.setId(asset.getId());
        dto.setAssetType("FIXED_INCOME");
        dto.setTicker(asset.getName());
        dto.setTotalInvested(asset.getInvestedAmount());
        dto.setCurrentValue(netValue.setScale(2, RoundingMode.HALF_UP));
        dto.setProfitOrLoss(netProfit.setScale(2, RoundingMode.HALF_UP));
        dto.setProfitability(profitability.setScale(2, RoundingMode.HALF_UP));
        return dto;
    }

    // ***** CORREÇÃO DA ASSINATURA *****
    // O parâmetro agora é a entidade correta: FixedIncomeAsset
    private BigDecimal calculateGrossValue(FixedIncomeAsset asset) {
        LocalDate startDate = asset.getInvestmentDate();
        LocalDate endDate = LocalDate.now();
        if (!endDate.isAfter(startDate)) return asset.getInvestedAmount();

        return switch (asset.getIndexType()) {
            case CDI -> calculateCdiGrossValue(asset, startDate, endDate);
            case PRE_FIXED -> calculatePrefixedGrossValue(asset, startDate, endDate);
            default -> asset.getInvestedAmount();
        };
    }

    // ***** CORREÇÃO DA ASSINATURA *****
    private BigDecimal calculateCdiGrossValue(FixedIncomeAsset asset, LocalDate startDate, LocalDate endDate) {
        Map<LocalDate, BigDecimal> cdiRates = indexService.getCdiRatesForPeriod(startDate, endDate);
        if (cdiRates.isEmpty()) return asset.getInvestedAmount();

        BigDecimal accumulatedFactor = BigDecimal.ONE;
        BigDecimal contractedRatePercentage = asset.getContractedRate().divide(new BigDecimal("100"), 10, RoundingMode.HALF_UP);

        for (LocalDate date = startDate; date.isBefore(endDate); date = date.plusDays(1)) {
            BigDecimal dailyCdi = cdiRates.get(date);
            if (dailyCdi != null) {
                BigDecimal dailyFactor = BigDecimal.ONE.add(dailyCdi.multiply(contractedRatePercentage));
                accumulatedFactor = accumulatedFactor.multiply(dailyFactor);
            }
        }
        return asset.getInvestedAmount().multiply(accumulatedFactor);
    }

    // ***** CORREÇÃO DA ASSINATURA *****
    private BigDecimal calculatePrefixedGrossValue(FixedIncomeAsset asset, LocalDate startDate, LocalDate endDate) {
        long days = businessDayService.countBusinessDays(startDate, endDate);
        double annualRate = asset.getContractedRate().divide(new BigDecimal("100"), 10, RoundingMode.HALF_UP).doubleValue();
        double dailyFactor = Math.pow(1 + annualRate, 1.0 / 252.0);
        double finalAmount = asset.getInvestedAmount().doubleValue() * Math.pow(dailyFactor, days);
        return BigDecimal.valueOf(finalAmount);
    }

    public BigDecimal getAllValue() {
        BigDecimal total = BigDecimal.ZERO;
         fixedIncomeRepository.findAll().stream()
                .map(this::calculatePosition)
                .map(AssetPositionDto::getCurrentValue)
                 .reduce(total, BigDecimal::add);

         return total;
    }
}