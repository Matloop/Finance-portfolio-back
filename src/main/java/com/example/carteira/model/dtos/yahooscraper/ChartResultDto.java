
package com.example.carteira.model.dtos.yahooscraper;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ChartResultDto(@JsonProperty("result") List<ChartDataDto> result) {}