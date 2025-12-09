import React from 'react';
import { AuthProvider } from '../context/AuthContext';
import DashboardApp from './DashboardApp';

export default function DashboardPage() {
    return (
        <AuthProvider>
            <DashboardApp />
        </AuthProvider>
    );
}