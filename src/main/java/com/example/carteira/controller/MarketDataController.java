package com.example.carteira.controller;

import com.example.carteira.model.dtos.AssetSearchResultDto;
import com.example.carteira.service.MarketDataService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/market-data")
public class MarketDataController {
    MarketDataService marketDataService;
    public MarketDataController(MarketDataService marketDataService) {
        this.marketDataService = marketDataService;
    }


    @GetMapping("/search/{term}")
    public Flux<AssetSearchResultDto> searchAssets(@PathVariable String term) {
        if (term == null || term.length() < 2) {
            return Flux.empty();
        }
        // A chamada agora funcionará, pois marketDataService não será mais nulo.
        return marketDataService.searchAssets(term);
    }

    @GetMapping("/price/{ticker}")
    public ResponseEntity<BigDecimal> getPriceForTicker(@PathVariable String ticker) {
        BigDecimal price = marketDataService.getPrice(ticker.toUpperCase());
        if (price.compareTo(BigDecimal.ZERO) == 0) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(price);
    }
}
