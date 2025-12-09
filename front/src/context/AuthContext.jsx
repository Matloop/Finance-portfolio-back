import React, { createContext, useState, useContext, useEffect, useCallback } from 'react';

// 1. Cria o Contexto
const AuthContext = createContext(null);

// 2. Cria o Provedor do Contexto
export const AuthProvider = ({ children }) => {
    const [token, setToken] = useState(null);
    const [isAuthenticated, setIsAuthenticated] = useState(false);
    const [isLoading, setIsLoading] = useState(true); // Estado de carregamento inicial

    // Verifica se já existe um token no localStorage quando o app carrega
    useEffect(() => {
        const storedToken = localStorage.getItem('jwt_token');
        if (storedToken) {
            setToken(storedToken);
            setIsAuthenticated(true);
        }
        setIsLoading(false); // Finaliza o carregamento inicial
    }, []);

    const login = useCallback((newToken) => {
        localStorage.setItem('jwt_token', newToken);
        setToken(newToken);
        setIsAuthenticated(true);
    }, []);

    const logout = useCallback(() => {
        localStorage.removeItem('jwt_token');
        setToken(null);
        setIsAuthenticated(false);
        window.location.href = '/';
    }, []);

    const value = {
        token,
        isAuthenticated,
        isLoading,
        login,
        logout
    };

    return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
};

// 3. Cria um Hook customizado para usar o contexto facilmente
export const useAuth = () => {
    return useContext(AuthContext);
};