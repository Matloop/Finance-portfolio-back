package com.example.carteira.service;

import com.example.carteira.model.Transaction;
import com.example.carteira.model.User;
import com.example.carteira.model.enums.AssetCategory;
import com.example.carteira.model.enums.AssetType;
import com.example.carteira.model.enums.Market;
import com.example.carteira.model.enums.TransactionType;
import com.example.carteira.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MarketDataServiceTest {
    @Mock
    private TransactionRepository transactionRepository;
    private static final String PRICE_CACHE_KEY = "market-prices";
    @Mock
    private List<MarketDataProvider> providers;
    @Mock
    private RedisTemplate<String,String> redisTemplate;
    @InjectMocks
    private MarketDataService marketDataService;
    @Mock
    private HashOperations<String,Object,Object> hashOperations;
    @Test
    void getPriceIfInCache() {
        String ticker = "AAPL";
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);

        when(hashOperations.get(PRICE_CACHE_KEY, ticker)).thenReturn("150.50");

        BigDecimal result = marketDataService.getPrice(ticker);
        assertEquals(new BigDecimal("150.50"), result);
        verify(hashOperations).get(PRICE_CACHE_KEY, ticker);
        verify(redisTemplate).opsForHash();
    }
    @Test
    void getPriceIfNotInCache() {
        String ticker = "AAPL";
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.get(PRICE_CACHE_KEY,ticker)).thenReturn(null);
        BigDecimal result = marketDataService.getPrice(ticker);
        assertEquals(BigDecimal.ZERO,result);

        verify(hashOperations).get(PRICE_CACHE_KEY, ticker);
        verify(redisTemplate).opsForHash();
    }

    @Test
    void invalidateCacheDoesCacheExists(){
        String ticker = "aapl";
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        marketDataService.invalidateCache(ticker);
        verify(redisTemplate).opsForHash();
        verify(hashOperations).delete(PRICE_CACHE_KEY, ticker.toUpperCase());
    }

    @Test
    void invalidateAllCache(){
        marketDataService.invalidateAllCache();
        verify(redisTemplate).delete(PRICE_CACHE_KEY);
    }

    @Test
    void getAllPrices(){
        Map<Object,Object> entries = Map.of(
                "AAPL","150",
                "TSLA", "250"

        );

        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.entries(PRICE_CACHE_KEY)).thenReturn(entries);

        Map<String, BigDecimal> result = marketDataService.getAllPrices();
        Map<String,BigDecimal> entriesToAssert = Map.of(
                "AAPL",new BigDecimal("150"),
                "TSLA", new BigDecimal("250")

        );
        assertEquals(result,entriesToAssert);

        verify(redisTemplate).opsForHash();
        verify(hashOperations).entries(PRICE_CACHE_KEY);
    }
    @Test
    void refreshAllMarketDataEmpty(){
        List<Transaction> transactions = List.of();
        when(transactionRepository.findAll()).thenReturn(transactions);
        marketDataService.refreshAllMarketData();
        verify(transactionRepository).findAll();
    }

    @Test
    void refreshAllMarketDataWithTransactions(){
        User user = new User();
        Transaction t1 = new Transaction(
                1L, // id
                "PETR4", // ticker
                Market.B3, // market
                AssetType.STOCK, // assetType
                new BigDecimal("5.25"), // otherCosts
                TransactionType.BUY, // transactionType
                new BigDecimal("100.00"), // quantity
                new BigDecimal("30.50"), // pricePerUnit
                LocalDate.of(2023, 10, 15), // transactionDate
                user, // user
                AssetCategory.EQUITY // assetCategory
        );

// --- Variable 2: Buying Cryptocurrency ---
        Transaction t2 = new Transaction(
                2L, // id
                "BTC", // ticker
                Market.CRYPTO, // market
                AssetType.CRYPTO, // assetType
                new BigDecimal("10.00"), // otherCosts
                TransactionType.BUY, // transactionType
                new BigDecimal("0.00500000"), // quantity (scale 8)
                new BigDecimal("27500.00"), // pricePerUnit
                LocalDate.of(2023, 11, 5), // transactionDate
                user, // user
                AssetCategory.CRYPTO // assetCategory
        );
        //Mockando com listas reais
        MarketDataProvider equitiesProvider = mock(MarketDataProvider.class);
        when(equitiesProvider.supports(AssetType.STOCK)).thenReturn(true);
        when(equitiesProvider.fetchPrices(anyList())).thenReturn(Flux.empty());
        MarketDataProvider cryptoProvider = mock(MarketDataProvider.class);
        when(cryptoProvider.supports(AssetType.CRYPTO)).thenReturn(true);
        when(cryptoProvider.fetchPrices(anyList())).thenReturn(Flux.empty());
        MarketDataService service = new MarketDataService(
                transactionRepository,
                List.of(equitiesProvider, cryptoProvider),
                redisTemplate
        );

        when(transactionRepository.findAll()).thenReturn(List.of(t1,t2));
        service.refreshAllMarketData();

        verify(marketDataService).updatePricesForTransactions(List.of(t1,t2));
    }

}