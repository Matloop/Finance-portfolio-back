// Em src/main/java/com/example/carteira/model/dtos/yahoo/
package com.example.carteira.model.dtos.yahooscraper;


import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ChartIndicatorsDto(@JsonProperty("quote") List<QuoteDataDto> quote) {}