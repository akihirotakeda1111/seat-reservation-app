import { useEffect, useRef, useState } from 'react';
import { ApiError } from '../api/client';
import {
  getAdminReservations,
  cancelAdminReservation,
} from '../api/adminReservations';
import type { AdminReservation } from '../types/adminReservation';
import type { ResultNotice } from '../types/reservation';
import { jaToday, validDate } from '../utils/jaDate';

export function useAdminReservations(
  token: string,
  onUnauthorized: () => void,
) {
  const [date, setDate] = useState(jaToday);
  const [today, setToday] = useState(jaToday);
  const [reservations, setReservations] = useState<AdminReservation[]>([]);
  const [loaded, setLoaded] = useState(false);
  const [loading, setLoading] = useState(false);
  const [cancelling, setCancelling] = useState(false);
  const [fetchError, setFetchError] = useState(false);
  const [lastChecked, setLastChecked] = useState<Date | null>(null);
  const [cancelTarget, setCancelTarget] = useState<AdminReservation | null>(
    null,
  );
  const [notice, setNotice] = useState<ResultNotice | null>(null);
  const epoch = useRef(0);
  const request = useRef<AbortController | null>(null);
  const busy = useRef(false);
  const denied = useRef(false);
  const mounted = useRef(true);
  useEffect(() => {
    mounted.current = true;
    const timer = setInterval(() => setToday(jaToday()), 1000);
    return () => {
      mounted.current = false;
      ++epoch.current;
      request.current?.abort();
      clearInterval(timer);
    };
  }, []);
  const past = validDate(date) && date < today;
  useEffect(() => {
    if (past) setCancelTarget(null);
  }, [past]);

  async function fetchReservations(preserveNotice = false) {
    if (busy.current || denied.current || !token || !validDate(date)) return;
    request.current?.abort();
    const controller = new AbortController();
    request.current = controller;
    const current = ++epoch.current;
    setLoading(true);
    setFetchError(false);
    setCancelTarget(null);
    if (!preserveNotice) setNotice(null);
    try {
      const result = await getAdminReservations(date, token, controller.signal);
      if (!mounted.current || current !== epoch.current) return;
      if (result.date !== date) throw new Error('Unexpected date');
      setReservations(
        [...result.reservations].sort((a, b) => a.seatNumber - b.seatNumber),
      );
      setLoaded(true);
      setLastChecked(new Date());
    } catch (error) {
      if (!mounted.current || current !== epoch.current) return;
      if (error instanceof ApiError && error.status === 401) {
        denied.current = true;
        onUnauthorized();
        return;
      }
      setFetchError(true);
      if (error instanceof ApiError && error.status === 403) {
        setReservations([]);
        setLoaded(false);
        setNotice({ kind: 'error', text: '管理者権限がありません。' });
      }
    } finally {
      if (mounted.current && current === epoch.current) setLoading(false);
    }
  }
  function changeDate(value: string) {
    if (busy.current || value === date) return;
    ++epoch.current;
    request.current?.abort();
    setDate(value);
    setReservations([]);
    setLoaded(false);
    setLoading(false);
    setLastChecked(null);
    setFetchError(false);
    setNotice(null);
    setCancelTarget(null);
  }
  function openCancelDialog(row: AdminReservation) {
    if (
      busy.current ||
      loading ||
      fetchError ||
      denied.current ||
      date < jaToday()
    )
      return;
    setCancelTarget(row);
  }
  function closeCancelDialog() {
    if (!busy.current) setCancelTarget(null);
  }
  async function confirmCancellation() {
    if (!cancelTarget || busy.current || denied.current) return;
    if (date < jaToday()) {
      setToday(jaToday());
      setCancelTarget(null);
      setNotice({ kind: 'error', text: '過去の予約はキャンセルできません。' });
      return;
    }
    busy.current = true;
    setCancelling(true);
    setNotice(null);
    try {
      await cancelAdminReservation(cancelTarget.id, token);
      if (!mounted.current) return;
      setNotice({
        kind: 'success',
        text: `座席 ${String(cancelTarget.seatNumber).padStart(2, '0')} の予約をキャンセルしました。`,
      });
    } catch (error) {
      if (!mounted.current) return;
      if (error instanceof ApiError && error.status === 401) {
        denied.current = true;
        onUnauthorized();
        return;
      }
      setNotice({
        kind: 'error',
        text:
          error instanceof ApiError && error.status < 500
            ? error.message
            : '操作の結果を確認できません。予約一覧を再取得して確認してください。',
      });
    } finally {
      busy.current = false;
      if (mounted.current) {
        setCancelling(false);
        setCancelTarget(null);
        if (!denied.current) await fetchReservations(true);
      }
    }
  }
  return {
    date,
    past,
    reservations,
    loaded,
    loading,
    cancelling,
    fetchError,
    lastChecked,
    cancelTarget,
    notice,
    changeDate,
    fetchReservations: () => fetchReservations(),
    openCancelDialog,
    closeCancelDialog,
    confirmCancellation,
  };
}
