package com.example.carteira.service;

import com.example.carteira.model.Asset;
import com.example.carteira.model.assets.Crypto;
import com.example.carteira.model.assets.FixedIncome;
import com.example.carteira.model.dtos.CreateCryptoDto;
import com.example.carteira.model.dtos.CreateFixedIncomeDto;
import com.example.carteira.model.dtos.DetailedAssetDto;
import com.example.carteira.repository.AssetRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
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
            DetailedAssetDto dto = new DetailedAssetDto();
            dto.setId(asset.getId());
            dto.setName(asset.getName());
            dto.setInvestedAmount(asset.getInvestedAmount());

            if (asset instanceof Crypto) {
                Crypto crypto = (Crypto) asset;
                dto.setType("Crypto");
                BigDecimal currentPrice = cryptoPriceService.getCurrentPrice(crypto.getTicker());
                BigDecimal grossValue = currentPrice.multiply(crypto.getQuantity());

                // Cripto não tem IR na fonte como Renda Fixa, então bruto = líquido por enquanto.
                dto.setGrossValue(grossValue.setScale(2, RoundingMode.HALF_UP));
                dto.setNetValue(grossValue.setScale(2, RoundingMode.HALF_UP));
                dto.setIncomeTax(BigDecimal.ZERO);

            } else if (asset instanceof FixedIncome) {
                FixedIncome fixedIncome = (FixedIncome) asset;
                dto.setType("Fixed Income");

                // Chama o serviço que agora retorna todos os valores
                FixedIncomeCalculationService.CalculationResult result = fixedIncomeCalculationService.calculateValues(fixedIncome);

                dto.setGrossValue(result.grossValue().setScale(2, RoundingMode.HALF_UP));
                dto.setNetValue(result.netValue().setScale(2, RoundingMode.HALF_UP));
                dto.setIncomeTax(result.incomeTax().setScale(2, RoundingMode.HALF_UP));
            }

            // Cálculos finais de lucro e rentabilidade baseados no valor LÍQUIDO
            BigDecimal netProfit = dto.getNetValue().subtract(dto.getInvestedAmount());
            dto.setNetProfit(netProfit.setScale(2, RoundingMode.HALF_UP));

            if (dto.getInvestedAmount().compareTo(BigDecimal.ZERO) != 0) {
                BigDecimal profitability = netProfit.divide(dto.getInvestedAmount(), 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100"));
                dto.setProfitability(profitability.setScale(2, RoundingMode.HALF_UP));
            } else {
                dto.setProfitability(BigDecimal.ZERO);
            }

            dtos.add(dto);
        }
        return dtos;
    }

    public Asset addCrypto(CreateCryptoDto dto) {
        Crypto crypto = new Crypto();
        crypto.setTicker(dto.getTicker().toUpperCase());
        crypto.setQuantity(dto.getQuantity());
        crypto.setInvestedAmount(dto.getInvestedAmount());

        // Se o nome não for fornecido, usa o ticker como nome.
        crypto.setName(dto.getName() != null && !dto.getName().isBlank() ? dto.getName() : dto.getTicker().toUpperCase());

        // Define a data de investimento como a data atual.
        crypto.setInvestmentDate(LocalDate.now());

        // Salva a nova entidade no banco de dados.
        return assetRepository.save(crypto);
    }

    /**
     * Remove um ativo do portfólio pelo seu ID.
     */
    public void deleteAsset(Long assetId) {
        // Verifica se o ativo existe antes de tentar deletar.
        if (!assetRepository.existsById(assetId)) {
            // Em um app real, você lançaria uma exceção mais específica.
            throw new IllegalArgumentException("Ativo com ID " + assetId + " não encontrado.");
        }
        assetRepository.deleteById(assetId);
    }

    public Asset addFixedIncome(CreateFixedIncomeDto dto) {
        FixedIncome fixedIncome = new FixedIncome();
        fixedIncome.setName(dto.getName());
        fixedIncome.setInvestedAmount(dto.getInvestedAmount());
        fixedIncome.setInvestmentDate(dto.getInvestmentDate());
        fixedIncome.setMaturityDate(dto.getMaturityDate());
        fixedIncome.setIndexType(dto.getIndexType());
        fixedIncome.setContractedRate(dto.getContractedRate());
        return assetRepository.save(fixedIncome);
    }
}