import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { SeatReservationPage } from './pages/SeatReservationPage';
import './styles.css';

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <SeatReservationPage />
  </StrictMode>,
);
