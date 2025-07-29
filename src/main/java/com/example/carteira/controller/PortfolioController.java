
package com.example.carteira.controller;


import com.example.carteira.model.dtos.DetailedAssetDto;
import com.example.carteira.service.PortfolioService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
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
}