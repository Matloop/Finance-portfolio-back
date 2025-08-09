package com.example.carteira.model.dtos;

import com.example.carteira.model.enums.AssetType;
import com.example.carteira.model.enums.TransactionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@Setter
public class CreateTransactionDto {
    @NotBlank
    private String ticker;
    @NotNull
    private AssetType assetType;
    @NotNull
    private TransactionType transactionType;
    @NotNull @Positive
    private BigDecimal quantity;
    @NotNull @Positive
    private BigDecimal pricePerUnit;
    @NotNull @PastOrPresent
    private LocalDate transactionDate;
}