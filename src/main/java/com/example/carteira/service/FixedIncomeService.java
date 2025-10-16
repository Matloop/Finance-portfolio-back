package com.example.carteira.service;

import com.example.carteira.model.FixedIncomeAsset;
import com.example.carteira.model.User;
import com.example.carteira.model.dtos.AssetPositionDto;
import com.example.carteira.model.dtos.CreateFixedIncomeDto;
import com.example.carteira.model.enums.AssetType;
import com.example.carteira.model.enums.FixedIncomeIndex;
import com.example.carteira.repository.FixedIncomeRepository;
import com.example.carteira.service.util.BusinessDayService;
import com.example.carteira.service.util.IncomeTaxService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;


import static com.example.carteira.model.enums.FixedIncomeIndex.*;

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

    private static final Set<AssetType> TAX_EXEMPT_TYPES = Set.of(
            AssetType.CRA,
            AssetType.CRI,
            AssetType.LCI,
            AssetType.LCA
    );

    public List<AssetPositionDto> getAllFixedIncomePositions() {
        return fixedIncomeRepository.findAll().stream()
                .map(this::calculatePosition)
                .collect(Collectors.toList());
    }

    public FixedIncomeAsset addFixedIncome(CreateFixedIncomeDto dto, User user) {
        FixedIncomeAsset asset = new FixedIncomeAsset();

        asset.setName(dto.getName());
        asset.setUser(user);
        asset.setAssetType(dto.getAssetType());
        asset.setInvestedAmount(dto.getInvestedAmount());
        asset.setInvestmentDate(dto.getInvestmentDate());
        asset.setDailyLiquid(dto.isDailyLiquid());
        asset.setMaturityDate(dto.getMaturityDate());
        asset.setIndexType(dto.getIndexType());

        BigDecimal rateToSet;

        // 1. Se o indexador for SELIC, o rendimento é 100% da taxa Selic.
        if (dto.getIndexType() == SELIC) {
            rateToSet = new BigDecimal("100");
        }
        // 2. Para outros indexadores (CDI, IPCA, etc.)...
        else {
        // Se o usuário forneceu uma taxa (ex: 110% do CDI), usamos a taxa dele.
        // Se o campo veio nulo/vazio, assumimos o padrão de 100%.
            rateToSet = (dto.getContractedRate() != null)
                    ? dto.getContractedRate()
                    : new BigDecimal("100");
        }

        asset.setContractedRate(rateToSet);

        return fixedIncomeRepository.save(asset);
    }


    public void deleteFixedIncome(Long id, User user) {
        FixedIncomeAsset asset = fixedIncomeRepository.findByIdAndUser(id, user).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ativo não encontrado"));
        fixedIncomeRepository.deleteById(id);
    }

    private AssetPositionDto calculatePosition(FixedIncomeAsset asset) {
        BigDecimal grossValue = calculateGrossValue(asset);
        BigDecimal grossProfit = grossValue.subtract(asset.getInvestedAmount());

        BigDecimal taxAmount = BigDecimal.ZERO;
        if (grossProfit.compareTo(BigDecimal.ZERO) > 0 && !TAX_EXEMPT_TYPES.contains(asset.getIndexType())){
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
        dto.setAssetType(asset.getAssetType());
        dto.setName(asset.getName());
        dto.setAssetType(asset.getAssetType());
        dto.setTicker(asset.getName());
        dto.setTotalInvested(asset.getInvestedAmount());
        dto.setCurrentValue(netValue.setScale(2, RoundingMode.HALF_UP));
        dto.setProfitOrLoss(netProfit.setScale(2, RoundingMode.HALF_UP));
        dto.setProfitability(profitability.setScale(2, RoundingMode.HALF_UP));
        return dto;
    }

    private BigDecimal calculateGrossValue(FixedIncomeAsset asset) {
        LocalDate startDate = asset.getInvestmentDate();
        LocalDate endDate = LocalDate.now();
        if (!endDate.isAfter(startDate)) return asset.getInvestedAmount();

        return switch (asset.getIndexType()) {
            case CDI, SELIC -> calculateCdiGrossValue(asset, startDate, endDate);
            case PRE_FIXED -> calculatePrefixedGrossValue(asset, startDate, endDate);

            default -> asset.getInvestedAmount();
        };
    }

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

    private BigDecimal calculatePrefixedGrossValue(FixedIncomeAsset asset, LocalDate startDate, LocalDate endDate) {
        long days = businessDayService.countBusinessDays(startDate, endDate);
        double annualRate = asset.getContractedRate().divide(new BigDecimal("100"), 10, RoundingMode.HALF_UP).doubleValue();
        double dailyFactor = Math.pow(1 + annualRate, 1.0 / 252.0);
        double finalAmount = asset.getInvestedAmount().doubleValue() * Math.pow(dailyFactor, days);
        return BigDecimal.valueOf(finalAmount);
    }

    public BigDecimal getAllValue() {
        return fixedIncomeRepository.findAll().stream()
                .map(this::calculatePosition)
                .map(AssetPositionDto::getCurrentValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public List<AssetPositionDto> getAllFixedIncomePositionsForDate(LocalDate calculationDate) {
        return fixedIncomeRepository.findAll().stream()
                .filter(asset -> !asset.getInvestmentDate().isAfter(calculationDate))
                .map(asset -> calculatePositionForDate(asset, calculationDate))
                .collect(Collectors.toList());
    }

    private AssetPositionDto calculatePositionForDate(FixedIncomeAsset asset, LocalDate calculationDate) {
        // Se a data de cálculo for anterior ao investimento, retorna null
        if (calculationDate.isBefore(asset.getInvestmentDate())) {
            return null;
        }

        // Se já atingiu a maturidade antes da data de cálculo, usa a data de maturidade
        LocalDate effectiveEndDate = calculationDate;
        if (asset.getMaturityDate() != null && calculationDate.isAfter(asset.getMaturityDate())) {
            effectiveEndDate = asset.getMaturityDate();
        }

        BigDecimal grossValue = calculateGrossValueForDate(asset, effectiveEndDate);
        BigDecimal grossProfit = grossValue.subtract(asset.getInvestedAmount());

        BigDecimal taxAmount = BigDecimal.ZERO;
        if (grossProfit.compareTo(BigDecimal.ZERO) > 0 && TAX_EXEMPT_TYPES.contains(asset.getIndexType())) {
            BigDecimal taxRate = incomeTaxService.getFixedIncomeTaxRate(
                    asset.getInvestmentDate(),
                    effectiveEndDate
            );
            taxAmount = grossProfit.multiply(taxRate).setScale(2, RoundingMode.HALF_UP);
        }

        BigDecimal netValue = grossValue.subtract(taxAmount);
        BigDecimal netProfit = netValue.subtract(asset.getInvestedAmount());
        BigDecimal profitability = asset.getInvestedAmount().compareTo(BigDecimal.ZERO) == 0 ?
                BigDecimal.ZERO :
                netProfit.divide(asset.getInvestedAmount(), 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100));

        AssetPositionDto dto = new AssetPositionDto();
        dto.setId(asset.getId());
        dto.setAssetType(asset.getAssetType());
        dto.setName(asset.getName());
        dto.setAssetType(asset.getAssetType());
        dto.setTicker(asset.getName());
        dto.setTotalInvested(asset.getInvestedAmount());
        dto.setCurrentValue(netValue.setScale(2, RoundingMode.HALF_UP));
        dto.setProfitOrLoss(netProfit.setScale(2, RoundingMode.HALF_UP));
        dto.setProfitability(profitability.setScale(2, RoundingMode.HALF_UP));
        return dto;
    }

    private BigDecimal calculateGrossValueForDate(FixedIncomeAsset asset, LocalDate endDate) {
        LocalDate startDate = asset.getInvestmentDate();
        if (!endDate.isAfter(startDate)) {
            return asset.getInvestedAmount();
        }

        return switch (asset.getIndexType()) {
            case CDI, SELIC -> calculateCdiGrossValueForDate(asset, startDate, endDate);
            case PRE_FIXED -> calculatePrefixedGrossValueForDate(asset, startDate, endDate);
            default -> asset.getInvestedAmount();
        };
    }

    private BigDecimal calculateCdiGrossValueForDate(FixedIncomeAsset asset, LocalDate startDate, LocalDate endDate) {
        Map<LocalDate, BigDecimal> cdiRates = indexService.getCdiRatesForPeriod(startDate, endDate);
        if (cdiRates.isEmpty()) {
            return asset.getInvestedAmount();
        }

        BigDecimal accumulatedFactor = BigDecimal.ONE;
        BigDecimal contractedRatePercentage = asset.getContractedRate()
                .divide(new BigDecimal("100"), 10, RoundingMode.HALF_UP);

        for (LocalDate date = startDate; date.isBefore(endDate); date = date.plusDays(1)) {
            BigDecimal dailyCdi = cdiRates.get(date);
            if (dailyCdi != null) {
                BigDecimal dailyFactor = BigDecimal.ONE.add(dailyCdi.multiply(contractedRatePercentage));
                accumulatedFactor = accumulatedFactor.multiply(dailyFactor);
            }
        }
        return asset.getInvestedAmount().multiply(accumulatedFactor);
    }

    private BigDecimal calculatePrefixedGrossValueForDate(FixedIncomeAsset asset, LocalDate startDate, LocalDate endDate) {
        long days = businessDayService.countBusinessDays(startDate, endDate);
        double annualRate = asset.getContractedRate()
                .divide(new BigDecimal("100"), 10, RoundingMode.HALF_UP)
                .doubleValue();
        double dailyFactor = Math.pow(1 + annualRate, 1.0 / 252.0);
        double finalAmount = asset.getInvestedAmount().doubleValue() * Math.pow(dailyFactor, days);
        return BigDecimal.valueOf(finalAmount);
    }


}