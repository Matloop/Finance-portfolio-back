
package com.example.carteira.model.dtos.yahooscraper;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record YahooChartResponseDto(ChartResultDto chart) {}