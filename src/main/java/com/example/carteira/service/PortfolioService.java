// Location: src/main/java/com/yourportfolio/service/PortfolioService.java
package com.example.carteira.service;


import com.example.carteira.model.Asset;
import com.example.carteira.model.assets.Crypto;
import com.example.carteira.model.assets.FixedIncome;
import com.example.carteira.model.dtos.DetailedAssetDto;
import com.example.carteira.repository.AssetRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

@Service
public class PortfolioService {

    @Autowired
    private AssetRepository assetRepository;

    @Autowired
    private CryptoPriceService cryptoPriceService;

    @Autowired
    private FixedIncomeCalculationService fixedIncomeCalculationService;

    public List<DetailedAssetDto> getDetailedPortfolio() {
        List<Asset> assets = assetRepository.findAll();
        List<DetailedAssetDto> dtos = new ArrayList<>();

        for (Asset asset : assets) {
            DetailedAssetDto dto = new DetailedAssetDto();
            dto.setId(asset.getId());
            dto.setName(asset.getName());
            dto.setInvestedAmount(asset.getInvestedAmount());

            BigDecimal currentValue = BigDecimal.ZERO;

            if (asset instanceof Crypto) {
                Crypto crypto = (Crypto) asset;
                BigDecimal currentPrice = cryptoPriceService.getCurrentPrice(crypto.getTicker());
                currentValue = currentPrice.multiply(crypto.getQuantity());
                dto.setType("Crypto");

            } else if (asset instanceof FixedIncome) {
                FixedIncome fixedIncome = (FixedIncome) asset;
                currentValue = fixedIncomeCalculationService.calculateCurrentValue(fixedIncome);
                dto.setType("Fixed Income");
            }

            dto.setCurrentValue(currentValue.setScale(2, RoundingMode.HALF_UP));

            BigDecimal profit = currentValue.subtract(asset.getInvestedAmount());
            dto.setProfit(profit.setScale(2, RoundingMode.HALF_UP));

            if (asset.getInvestedAmount().compareTo(BigDecimal.ZERO) != 0) {
                BigDecimal profitability = profit.divide(asset.getInvestedAmount(), 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100"));
                dto.setProfitability(profitability.setScale(2, RoundingMode.HALF_UP));
            } else {
                dto.setProfitability(BigDecimal.ZERO);
            }

            dtos.add(dto);
        }
        return dtos;
    }
}
