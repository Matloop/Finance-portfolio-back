package com.example.carteira.controller;

import com.example.carteira.model.Transaction;
import com.example.carteira.model.User;
import com.example.carteira.model.dtos.InvestedDetailDto;
import com.example.carteira.model.dtos.PortfolioDashboardDto;
import com.example.carteira.model.dtos.PortfolioEvolutionDto;
import com.example.carteira.model.dtos.TagAssetRequestDto;
import com.example.carteira.model.enums.AssetType;
import com.example.carteira.service.MarketDataService;
import com.example.carteira.service.PortfolioService;
import com.example.carteira.service.UserAssetPreferenceService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/portfolio")
public class PortfolioController {
    private final PortfolioService portfolioService;
    private final MarketDataService marketDataService;
    private final UserAssetPreferenceService userAssetPreferenceService;

    public PortfolioController(PortfolioService portfolioService, MarketDataService marketDataService, UserAssetPreferenceService userAssetPreferenceService) {
        this.portfolioService = portfolioService;
        this.marketDataService = marketDataService;
        this.userAssetPreferenceService = userAssetPreferenceService;
    }


    @PostMapping("/refresh")
    public ResponseEntity<Map<String, String>> refreshData() {
        marketDataService.refreshAllMarketData();
        return ResponseEntity.ok(Map.of("message", "A atualização dos dados de mercado foi iniciada."));
    }

    @GetMapping("/dashboard")
    public ResponseEntity<PortfolioDashboardDto> getPortfolioDashboard(@AuthenticationPrincipal User user) {
        // A chamada ao método principal do serviço está correta.
        return ResponseEntity.ok(portfolioService.getPortfolioDashboardData(user));
    }
    @GetMapping("/evolution/mwr")
    public ResponseEntity<PortfolioEvolutionDto> getMWREvolutionData(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String assetType, // Renomeado de subFilter
            @RequestParam(required = false) String ticker,
            @AuthenticationPrincipal User user
    ) {
        return ResponseEntity.ok(portfolioService.getPortfolioEvolutionData(user, category, assetType, ticker));
    }

    @GetMapping("/evolution/twr")
    public ResponseEntity<PortfolioEvolutionDto> getTWREvolutionData(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String assetType, // Renomeado de subFilter
            @RequestParam(required = false) String ticker,
            @AuthenticationPrincipal User user
    ) {
        return ResponseEntity.ok(portfolioService.getPortfolioEvolutionData(user, category, assetType, ticker));
    }

    @GetMapping("/invested-details")
    public ResponseEntity<List<InvestedDetailDto>> getInvestedValueDetails(@AuthenticationPrincipal User user) {
        List<InvestedDetailDto> details = portfolioService.getInvestedValueDetails(user);
        return ResponseEntity.ok(details);
    }

    @GetMapping("/transactions/{identifier}")
    public ResponseEntity<List<Transaction>> getTransactionsForAsset(
            @PathVariable String identifier,
            @RequestParam AssetType assetType,
            @AuthenticationPrincipal User user) {

        List<Transaction> transactions = portfolioService.getTransactionsForAsset(user, identifier, assetType);
        return ResponseEntity.ok(transactions);
    }

    @DeleteMapping("/assets/{identifier}")
    public ResponseEntity<Void> deleteAsset(
            @PathVariable String identifier,
            @RequestParam AssetType assetType,
            @AuthenticationPrincipal User user) {

        portfolioService.deleteAsset(user, identifier, assetType);
        return ResponseEntity.noContent().build(); // Retorna 204 No Content, o padrão para DELETE
    }

    @PostMapping("/preferences/tag-asset")
    public ResponseEntity<Void> saveUserCashPreference(@AuthenticationPrincipal User user, @RequestBody TagAssetRequestDto dto){
        userAssetPreferenceService.tagAssetAsCash(user, dto.getAssetIdentifier(), dto.isCash());
        return ResponseEntity.ok().build();
    }



}