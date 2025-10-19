package com.example.carteira;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@EnableCaching
public class CarteiraApplication {

    public static void main(String[] args) {
        SpringApplication.run(CarteiraApplication.class, args);
    }

}
