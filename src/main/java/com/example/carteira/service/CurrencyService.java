package com.example.carteira.service;

import com.example.carteira.model.dtos.AssetSearchResultDto;
import com.example.carteira.model.dtos.AssetToFetch;
import com.example.carteira.model.dtos.PriceData;
import com.example.carteira.model.enums.AssetType;
import com.example.carteira.service.util.ExchangeRateService;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Service

public class CurrencyService implements MarketDataProvider {
    private final ExchangeRateService exchangeRateService;

    public CurrencyService(ExchangeRateService exchangeRateService) {
        this.exchangeRateService = exchangeRateService;
    }

    @Override
    public Flux<PriceData> fetchPrices(List<AssetToFetch> assetsToFetch) {
        if (assetsToFetch.isEmpty()) return Flux.empty();
        Optional<AssetToFetch> dollarAssetOptional = assetsToFetch.stream()
                .filter(asset -> asset.assetType().equals(AssetType.DOLLAR))
                .findFirst();
        if(dollarAssetOptional.isPresent()){
            AssetToFetch dollarAsset = dollarAssetOptional.get();

            return exchangeRateService.fetchUsdToBrlRate()
                    .map(price -> new PriceData(
                            dollarAsset.ticker(),
                            price
                    )).flux();
        }

        return Flux.empty();
    }

    @Override
    public boolean supports(AssetType assetType) {
        return assetType == assetType.DOLLAR;
    }

    @Override
    public Mono<PriceData> fetchHistoricalPrice(AssetToFetch assetToFetch, LocalDate date) {
        return exchangeRateService.fetchHistoricalUsdToBrlRate(date)
                .map(price -> new PriceData(
                        assetToFetch.ticker(),
                        price
                        )
                );
    }

    @Override
    public Mono<Void> initialize() {
        return null;
    }

    @Override
    public Flux<AssetSearchResultDto> search(String term) {
        return null;
    }


}
