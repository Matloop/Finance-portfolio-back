package com.example.carteira.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker // Habilita o processamento de mensagens WebSocket, com um broker por trás
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // Habilita um broker de mensagens simples na memória para levar as mensagens de volta ao cliente
        config.enableSimpleBroker("/topic");
        // Define o prefixo "/app" para mensagens que são vinculadas a métodos anotados com @MessageMapping.
        // Não usaremos @MessageMapping neste exemplo, mas é uma boa prática configurar.
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Registra o endpoint "/ws" que os clientes usarão para se conectar ao nosso servidor WebSocket.
        // withSockJS() oferece uma opção de fallback para navegadores que não suportam WebSocket.
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*") // MUDANÇA AQUI
                .withSockJS();
    }
}