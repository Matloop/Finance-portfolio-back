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
import java.util.stream.Collectors;

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
            // 1. Crie um novo DTO para cada ativo no loop.
            DetailedAssetDto dto = new DetailedAssetDto();
            dto.setId(asset.getId());
            dto.setName(asset.getName());
            dto.setInvestedAmount(asset.getInvestedAmount());

            // 2. Inicialize o valor atual para este ativo.
            BigDecimal currentValue = BigDecimal.ZERO;

            // 3. Verifique o tipo do ativo e calcule seu valor atual.
            if (asset instanceof Crypto) {
                Crypto crypto = (Crypto) asset;
                // Busca o preço do cache, que é atualizado em tempo real no background.
                BigDecimal currentPrice = cryptoPriceService.getCurrentPrice(crypto.getTicker());
                currentValue = currentPrice.multiply(crypto.getQuantity());
                dto.setType("Crypto");

            } else if (asset instanceof FixedIncome) {
                FixedIncome fixedIncome = (FixedIncome) asset;
                currentValue = fixedIncomeCalculationService.calculateCurrentValue(fixedIncome);
                dto.setType("Fixed Income");
            }

            // 4. Calcule o lucro e a rentabilidade com base no valor atual.
            dto.setCurrentValue(currentValue.setScale(2, RoundingMode.HALF_UP));

            BigDecimal profit = currentValue.subtract(asset.getInvestedAmount());
            dto.setProfit(profit.setScale(2, RoundingMode.HALF_UP));

            // Evita divisão por zero se o valor investido for 0.
            if (asset.getInvestedAmount().compareTo(BigDecimal.ZERO) != 0) {
                BigDecimal profitability = profit.divide(asset.getInvestedAmount(), 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100"));
                dto.setProfitability(profitability.setScale(2, RoundingMode.HALF_UP));
            } else {
                dto.setProfitability(BigDecimal.ZERO);
            }

            // 5. Adicione o DTO preenchido à lista.
            dtos.add(dto);
        }
        return dtos;
    }
}