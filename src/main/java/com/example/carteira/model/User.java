package com.example.carteira.model;

import jakarta.persistence.*;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.Collections;
import java.util.Objects;

@Entity
@Table(name = "users") // Define o nome da tabela no banco de dados
public class User implements UserDetails {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String password;

    // Construtor vazio exigido pelo JPA
    public User() {
    }

    // Getters e Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @Override
    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    // --- MÉTODOS DA INTERFACE UserDetails ---

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        // Para uma aplicação simples, podemos retornar uma lista vazia.
        // Se você tivesse roles (ADMIN, USER), você as configuraria aqui.
        return Collections.emptyList();
    }

    @Override
    public String getUsername() {
        // O Spring Security usa "username" como identificador principal.
        // No nosso caso, o email é o nosso identificador único.
        return this.email;
    }

    @Override
    public boolean isAccountNonExpired() {
        // Para uma aplicação simples, retornamos true.
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        // Retornamos true para não ter contas bloqueadas.
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        // Retornamos true para as credenciais não expirarem.
        return true;
    }

    @Override
    public boolean isEnabled() {
        // Retornamos true para contas habilitadas.
        return true;
    }

    // --- Métodos equals e hashCode para consistência ---

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        User user = (User) o;
        return Objects.equals(id, user.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}