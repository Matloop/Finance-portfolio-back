package com.example.carteira.service.util;

import com.example.carteira.model.dtos.HolidayDto;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
public class BusinessDayService {

    private final WebClient webClient;
    // Usamos um Set para armazenar os feriados cacheados para performance de busca O(1).
    // O ConcurrentHashMap.newKeySet() cria um Set thread-safe.
    private final Set<LocalDate> holidaysCache = ConcurrentHashMap.newKeySet();

    public BusinessDayService(WebClient.Builder webClientBuilder) {
        // Criamos um WebClient específico para a BrasilAPI.
        this.webClient = webClientBuilder.baseUrl("https://brasilapi.com.br/api/feriados/v1").build();
    }

    /**
     * Este método é executado automaticamente uma vez, após a inicialização do serviço.
     * Ele busca todos os feriados para o intervalo de anos definido e os armazena em cache.
     */
    @PostConstruct
    public void loadHolidays() {
        int startYear = 2024;
        int endYear = 2030;
        System.out.println("Iniciando carregamento de feriados nacionais de " + startYear + " a " + endYear + "...");

        // Cria uma lista de tarefas (API calls) para cada ano.
        List<Mono<Void>> tasks = IntStream.rangeClosed(startYear, endYear)
                .mapToObj(year -> fetchHolidaysForYear(year).then())
                .collect(Collectors.toList());

        try {
            // Executa todas as tarefas em paralelo e espera que todas terminem.
            Mono.when(tasks).block();
            System.out.println("Sucesso! " + holidaysCache.size() + " feriados carregados e cacheados.");
        } catch (Exception e) {
            System.err.println("!!!!! FALHA CRÍTICA AO CARREGAR FERIADOS !!!!!");
            System.err.println("A aplicação continuará, mas os cálculos de dias úteis podem estar incorretos.");
            e.printStackTrace();
        }
    }

    /**
     * Busca os feriados para um ano específico na BrasilAPI e os adiciona ao cache.
     */
    private Flux<Void> fetchHolidaysForYear(int year) {
        return webClient.get()
                .uri("/{year}", year)
                .retrieve()
                .bodyToFlux(HolidayDto.class) // Converte a resposta JSON em um Flux de HolidayDto
                .map(holiday -> LocalDate.parse(holiday.date())) // Mapeia cada DTO para um objeto LocalDate
                .doOnNext(holidaysCache::add) // Adiciona cada data de feriado ao nosso Set de cache
                .thenMany(Flux.empty()); // Retorna um Flux vazio após completar
    }


    /**
     * Verifica se uma data é um dia útil (não é fim de semana nem feriado nacional).
     */
    public boolean isBusinessDay(LocalDate date) {
        if (date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return false;
        }
        return !holidaysCache.contains(date);
    }

    /**
     * Conta o número de dias úteis entre duas datas (exclusivo da data final).
     */
    public long countBusinessDays(LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null || !endDate.isAfter(startDate)) {
            return 0;
        }
        return startDate.datesUntil(endDate)
                .filter(this::isBusinessDay)
                .count();
    }
}