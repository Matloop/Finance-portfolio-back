
package com.example.carteira.controller;


import com.example.carteira.model.Asset;
import com.example.carteira.model.dtos.CreateCryptoDto;
import com.example.carteira.model.dtos.CreateFixedIncomeDto;
import com.example.carteira.model.dtos.DetailedAssetDto;
import com.example.carteira.service.PortfolioService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/portfolio")
public class PortfolioController {

    @Autowired
    private PortfolioService portfolioService;

    @GetMapping("/detailed")
    public List<DetailedAssetDto> getDetailedPortfolio() {
        return portfolioService.getDetailedPortfolio();
    }
    @PostMapping("/crypto")
    public ResponseEntity<Asset> addCrypto(@Valid @RequestBody CreateCryptoDto createCryptoDto) {
        Asset newAsset = portfolioService.addCrypto(createCryptoDto);
        return new ResponseEntity<>(newAsset, HttpStatus.CREATED);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteAsset(@PathVariable Long id) {
        portfolioService.deleteAsset(id);
        return ResponseEntity.noContent().build(); // Retorna 204 No Content, indicando sucesso.
    }
    @PostMapping("/fixed-income")
    public ResponseEntity<Asset> addFixedIncome(@Valid @RequestBody CreateFixedIncomeDto dto) {
        Asset newAsset = portfolioService.addFixedIncome(dto);
        return new ResponseEntity<>(newAsset, HttpStatus.CREATED);
    }
}