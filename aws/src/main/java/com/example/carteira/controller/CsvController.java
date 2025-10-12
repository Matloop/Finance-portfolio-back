// Crie este novo arquivo em: src/main/java/com/example/carteira/controller/CsvController.java
package com.example.carteira.controller;

import com.example.carteira.model.dtos.ImportSummaryDto;
import com.example.carteira.service.CsvService;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/csv") // Um novo caminho base para manter a organização
public class CsvController {

    private final CsvService csvService;

    public CsvController(CsvService csvService) {
        this.csvService = csvService;
    }

    /**
     * Endpoint para exportar todas as transações da carteira em um arquivo CSV.
     * @return Uma resposta HTTP com o arquivo CSV para download.
     */
    @GetMapping("/export/transactions")
    public ResponseEntity<Resource> exportTransactions() {
        String csvContent = csvService.exportTransactionsToCsv();

        // Converte a string CSV em um recurso que pode ser enviado na resposta.
        InputStreamResource resource = new InputStreamResource(
                new ByteArrayInputStream(csvContent.getBytes(StandardCharsets.UTF_8))
        );

        // Configura os cabeçalhos HTTP para forçar o download no navegador.
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=carteira_transacoes.csv");

        return ResponseEntity.ok()
                .headers(headers)
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(resource);
    }
    @PostMapping("/import/transactions")
    public ResponseEntity<ImportSummaryDto> importTransactions(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(
                    new ImportSummaryDto(0, 1, List.of("O arquivo enviado está vazio."))
            );
        }

        try {
            ImportSummaryDto summary = csvService.importTransactionsFromCsv(file.getInputStream());
            return ResponseEntity.ok(summary);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(
                    new ImportSummaryDto(0, 1, List.of("Erro no servidor ao processar o arquivo: " + e.getMessage()))
            );
        }
    }
}