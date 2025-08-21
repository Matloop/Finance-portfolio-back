package com.example.carteira.controller;

import com.example.carteira.model.Transaction;
import com.example.carteira.model.dtos.AssetPercentage;
import com.example.carteira.model.dtos.AssetPositionDto;
import com.example.carteira.model.dtos.PortfolioSummaryDto;
import com.example.carteira.model.dtos.TransactionRequest;
import com.example.carteira.service.MarketDataService;
import com.example.carteira.service.PortfolioService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/portfolio")
public class PortfolioController {
    private final PortfolioService portfolioService;
    private final MarketDataService marketDataService;

    public PortfolioController(PortfolioService portfolioService, MarketDataService marketDataService) {
        this.portfolioService = portfolioService;
        this.marketDataService = marketDataService;
    }

    @GetMapping
    public List<AssetPositionDto> getConsolidatedPortfolio() {
        return portfolioService.getConsolidatedPortfolio();
    }

    @PostMapping("/refresh")
    public ResponseEntity<Map<String, String>> refreshData() {
        marketDataService.refreshAllMarketData();
        return ResponseEntity.ok(Map.of("message", "A atualização dos dados de mercado foi iniciada."));
    }
    @GetMapping("/summary")
    public ResponseEntity<PortfolioSummaryDto> getPortfolioSummary() {
        return ResponseEntity.ok(portfolioService.getPortfolioSummary());
    }

    @GetMapping("/assets")
    public ResponseEntity<Map<String, List<AssetPositionDto>>> getAssetsGrouped() {
        return ResponseEntity.ok(portfolioService.getConsolidatedPortfolioGrouped());
    }

    @GetMapping("/assetPercentage")
    public ResponseEntity<AssetPercentage> getAssetPercentage() {
        return ResponseEntity.ok(portfolioService.getAssetPercentage());
    }
}