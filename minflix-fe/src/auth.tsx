import { createContext, useContext, useState, type ReactNode } from 'react';
import { clearToken, getToken, setToken } from './api';

interface Auth {
  isAuthenticated: boolean;
  login: (token: string) => void;
  logout: () => void;
}

const AuthContext = createContext<Auth>(null!);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [token, setTokenState] = useState(getToken);

  const login = (newToken: string) => {
    setToken(newToken);
    setTokenState(newToken);
  };
  const logout = () => {
    clearToken();
    setTokenState(null);
  };

  return <AuthContext.Provider value={{ isAuthenticated: token !== null, login, logout }}>{children}</AuthContext.Provider>;
}

export const useAuth = () => useContext(AuthContext);
