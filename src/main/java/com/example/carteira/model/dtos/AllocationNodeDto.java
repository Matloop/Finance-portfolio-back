// Em src/main/java/com/example/carteira/model/dtos/AllocationNodeDto.java
package com.example.carteira.model.dtos;

import java.math.BigDecimal;
import java.util.Map;

public class AllocationNodeDto {
    private BigDecimal percentage;
    private Map<String, AllocationNodeDto> children;

    // Construtores
    public AllocationNodeDto(BigDecimal percentage, Map<String, AllocationNodeDto> children) {
        this.percentage = percentage;
        this.children = children;
    }

    public AllocationNodeDto(BigDecimal percentage) {
        this.percentage = percentage;
        this.children = null; // Um nó final não tem filhos
    }

    // Getters e Setters
    public BigDecimal getPercentage() { return percentage; }
    public void setPercentage(BigDecimal percentage) { this.percentage = percentage; }
    public Map<String, AllocationNodeDto> getChildren() { return children; }
    public void setChildren(Map<String, AllocationNodeDto> children) { this.children = children; }
}