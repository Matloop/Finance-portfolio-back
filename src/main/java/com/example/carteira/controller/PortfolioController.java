package com.example.carteira.controller;

import com.example.carteira.model.dtos.AssetPositionDto;
import com.example.carteira.service.PortfolioService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/portfolio")
public class PortfolioController {
    private final PortfolioService portfolioService;

    public PortfolioController(PortfolioService portfolioService) {
        this.portfolioService = portfolioService;
    }

    @GetMapping
    public List<AssetPositionDto> getConsolidatedPortfolio() {
        return portfolioService.getConsolidatedPortfolio();
    }
}