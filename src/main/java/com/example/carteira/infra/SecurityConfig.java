package com.example.carteira.infra;

import com.example.carteira.model.User;
import com.example.carteira.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Arrays;
import java.util.UUID;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Autowired
    private SecurityFilter securityFilter;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TokenService tokenService;

    // Injeta a URL de redirecionamento do application.properties
    @Value("${app.oauth2.frontend-redirect-uri}")
    private String frontendRedirectUri;


    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http

                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .headers(headers -> headers
                        .frameOptions(frameOptions -> frameOptions
                                .sameOrigin() // Permite frames da mesma origem, ou .disable() para desabilitar totalmente
                        )
                )
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .authorizeHttpRequests(authorize -> authorize
                        // Permite o acesso aos endpoints de login/erro do Spring OAuth2
                        .requestMatchers("/oauth2/**", "/login/**", "/error", "/h2-console/**").permitAll()
                        .requestMatchers("/api/market-data/search/**").permitAll()
                        // Protege todas as outras rotas /api
                        .requestMatchers("/api/**").authenticated()
                        // Permite qualquer outra requisição
                        .anyRequest().permitAll()
                )
                // Se uma requisição não autenticada tentar acessar /api, retorna 401
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                )
                // Configura o login OAuth2
                .oauth2Login(oauth2 -> oauth2
                        .successHandler(oauth2AuthenticationSuccessHandler())
                        .failureHandler((request, response, exception) -> {
                            // Log do erro para debug
                            exception.printStackTrace();
                            // Redireciona para o frontend com erro
                            String errorUrl = frontendRedirectUri.replace("/dashboard", "") + "?error=login_failed";
                            response.sendRedirect(errorUrl);
                        })
                )
                // Adiciona nosso filtro para validar JWTs
                .addFilterBefore(securityFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        // SUAS URLs JÁ EXISTENTES
        configuration.setAllowedOrigins(Arrays.asList(
                "http://localhost:3000",
                "http://localhost:3002",
                "https://finance-portfolio-front-2.onrender.com",
                "https://main.d1xdpfpvrjro0q.amplifyapp.com",
                "https://finance-portfolio-front-2.vercel.app"
        ));
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(Arrays.asList("*")); // Permitir todos os cabeçalhos
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        // Aplica essa configuração para todas as rotas
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    // Handler que é executado após o login com o Google ser bem-sucedido
    @Bean
    public AuthenticationSuccessHandler oauth2AuthenticationSuccessHandler() {
        return (request, response, authentication) -> {
            try {
                System.out.println("=== OAuth2 Success Handler Iniciado ===");

                OAuth2User oauthUser = (OAuth2User) authentication.getPrincipal();
                String email = oauthUser.getAttribute("email");
                String name = oauthUser.getAttribute("name");

                System.out.println("Email: " + email);
                System.out.println("Nome: " + name);

                // Procura o usuário no banco, ou cria um novo se não existir
                User user = userRepository.findByEmail(email)
                        .orElseGet(() -> {
                            System.out.println("Criando novo usuário...");
                            User newUser = new User();
                            newUser.setEmail(email);
                            newUser.setName(name);
                            newUser.setPassword(passwordEncoder().encode(UUID.randomUUID().toString()));
                            return userRepository.save(newUser);
                        });

                System.out.println("Usuário obtido/criado: " + user.getEmail());

                // Gera um token JWT para o usuário
                String jwtToken = tokenService.generateToken(user);
                System.out.println("Token gerado: " + jwtToken.substring(0, 20) + "...");

                // Redireciona o usuário de volta para o frontend, passando o token na URL
                String targetUrl = UriComponentsBuilder.fromUriString(frontendRedirectUri)
                        .queryParam("token", jwtToken)
                        .build().toUriString();

                System.out.println("Redirecionando para: " + targetUrl);
                response.sendRedirect(targetUrl);

            } catch (Exception e) {
                System.err.println("Erro no OAuth2 Success Handler:");
                e.printStackTrace();
                // Em caso de erro, redireciona para o frontend com mensagem de erro
                response.sendRedirect(frontendRedirectUri.replace("/dashboard", "") + "?error=token_generation_failed");
            }
        };
    }

    // Bean para criptografar senhas (ainda útil para a senha aleatória)
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}