import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App';
import { setLanguage } from './strings';
import { locale, watchTheme } from './theme';
import './styles.css';

watchTheme();
setLanguage(locale());

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
