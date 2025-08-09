package com.example.carteira.controller;


import com.example.carteira.model.FixedIncomeAsset;
import com.example.carteira.model.dtos.CreateFixedIncomeDto;
import com.example.carteira.service.FixedIncomeService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/fixed-income")
public class FixedIncomeController {

    private final FixedIncomeService fixedIncomeService;

    public FixedIncomeController(FixedIncomeService fixedIncomeService) {
        this.fixedIncomeService = fixedIncomeService;
    }

    @PostMapping
    public ResponseEntity<FixedIncomeAsset> addFixedIncome(@Valid @RequestBody CreateFixedIncomeDto dto) {
        return new ResponseEntity<>(fixedIncomeService.addFixedIncome(dto), HttpStatus.CREATED);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteFixedIncome(@PathVariable Long id) {
        fixedIncomeService.deleteFixedIncome(id);
        return ResponseEntity.noContent().build();
    }
}