// Location: src/main/java/com/yourportfolio/service/CryptoPriceService.java
package com.example.carteira.service;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.util.Random;

@Service
public class CryptoPriceService {


    public BigDecimal getCurrentPrice(String ticker) {
        System.out.println("SIMULATING price lookup for: " + ticker);
        if ("BTC".equalsIgnoreCase(ticker)) {
            // Simulates a small price variation for Bitcoin
            return new BigDecimal("65000.00").add(new BigDecimal(new Random().nextInt(200)));
        }
        if ("ETH".equalsIgnoreCase(ticker)) {
            // Simulates a small price variation for Ethereum
            return new BigDecimal("3500.00").add(new BigDecimal(new Random().nextInt(50)));
        }
        return BigDecimal.ZERO; // Return 0 if not found
        // --- END OF SIMULATION ---
    }
}