
package com.example.carteira.model.dtos.yahooscraper;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ChartDataDto(List<Long> timestamp, ChartIndicatorsDto indicators) {}