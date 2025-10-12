package com.example.carteira.infra;

import com.example.carteira.model.User;
import com.example.carteira.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
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
import org.springframework.web.util.UriComponentsBuilder;

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
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        // Permite o acesso aos endpoints de login do Spring OAuth2
                        .requestMatchers("/oauth2/**", "/login/oauth2/code/**").permitAll()
                        // Protege todas as outras rotas /api
                        .requestMatchers("/api/**").authenticated()
                        // Permite qualquer outra requisição (se houver, como a raiz "/")
                        .anyRequest().permitAll()
                )
                // Se uma requisição não autenticada tentar acessar /api, retorna 401 Unauthorized
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                )
                // Configura o login OAuth2
                .oauth2Login(oauth2 -> oauth2
                        .successHandler(oauth2AuthenticationSuccessHandler()) // Nosso handler customizado
                )
                // Adiciona nosso filtro para validar JWTs em cada requisição
                .addFilterBefore(securityFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    // Handler que é executado após o login com o Google ser bem-sucedido
    @Bean
    public AuthenticationSuccessHandler oauth2AuthenticationSuccessHandler() {
        return (request, response, authentication) -> {
            OAuth2User oauthUser = (OAuth2User) authentication.getPrincipal();
            String email = oauthUser.getAttribute("email");
            String name = oauthUser.getAttribute("name");

            // Procura o usuário no banco, ou cria um novo se não existir
            User user = userRepository.findByEmail(email) // Adapte o método (ex: findByUsername)
                    .orElseGet(() -> {
                        User newUser = new User();
                        newUser.setEmail(email); // Adapte os setters
                        newUser.setName(name);   // Adapte os setters
                        // Senha aleatória, já que o login é via Google
                        newUser.setPassword(passwordEncoder().encode(UUID.randomUUID().toString()));
                        return userRepository.save(newUser);
                    });

            // Gera um token JWT para o usuário
            String jwtToken = tokenService.generateToken(user);

            // Redireciona o usuário de volta para o frontend, passando o token na URL
            String targetUrl = UriComponentsBuilder.fromUriString(frontendRedirectUri)
                    .queryParam("token", jwtToken)
                    .build().toUriString();
            response.sendRedirect(targetUrl);
        };
    }

    // Bean para criptografar senhas (ainda útil para a senha aleatória)
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}