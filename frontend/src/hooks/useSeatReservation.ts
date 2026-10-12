import { useEffect, useRef, useState } from 'react';
import {
  ApiError,
  cancelReservation,
  getSeats,
  reserveSeat,
} from '../api/reservations';
import type { DemoUser, ResultNotice, Seat } from '../types/reservation';
import { jaToday, validDate } from '../utils/jaDate';

const POLL_MS = 5_000;
const sameSeats = (a: Seat[], b: Seat[]) =>
  a.length === b.length &&
  a.every(
    (seat, i) =>
      seat.seatNumber === b[i].seatNumber &&
      seat.status === b[i].status &&
      seat.reservationId === b[i].reservationId,
  );

export function useSeatReservation() {
  const [date, setDate] = useState(jaToday);
  const [user, setUser] = useState<DemoUser>('demo-user-1');
  const [today, setToday] = useState(jaToday);
  const [seats, setSeats] = useState<Seat[]>([]);
  const [selectedNumber, setSelectedNumber] = useState<number | null>(null);
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [fetchError, setFetchError] = useState(false);
  const [lastChecked, setLastChecked] = useState<Date | null>(null);
  const [notice, setNotice] = useState<ResultNotice | null>(null);
  const [reload, setReload] = useState(0);
  const selectedRef = useRef<Seat | null>(null);
  const busyRef = useRef(false);
  const generation = useRef(0);
  const requestRef = useRef<AbortController | null>(null);
  const mounted = useRef(true);

  const clearSelection = () => {
    selectedRef.current = null;
    setSelectedNumber(null);
  };

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

  useEffect(() => {
    const checkDay = () => setToday(jaToday());
    const timer = setInterval(checkDay, 1_000);
    document.addEventListener('visibilitychange', checkDay);
    return () => {
      clearInterval(timer);
      document.removeEventListener('visibilitychange', checkDay);
    };
  }, []);

  useEffect(() => {
    if (date < today) clearSelection();
  }, [date, today]);

  useEffect(() => {
    const current = ++generation.current;
    let stopped = false;
    let inFlight = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const active = () => !stopped && generation.current === current;
    if (!validDate(date) || submitting) {
      setLoading(false);
      return;
    }
    setLoading(true);

    const refresh = async () => {
      if (!active() || inFlight || busyRef.current || document.hidden) return;
      inFlight = true;
      const controller = new AbortController();
      requestRef.current = controller;
      try {
        const result = await getSeats(date, user, controller.signal);
        if (!active()) return;
        if (result.date !== date) throw new Error('Unexpected response date');
        const old = selectedRef.current;
        const next = result.seats.find((s) => s.seatNumber === old?.seatNumber);
        if (
          old &&
          (!next ||
            next.status !== old.status ||
            next.reservationId !== old.reservationId)
        ) {
          clearSelection();
          setNotice({
            kind: 'info',
            text: '選択中の座席情報が更新されました。',
          });
        }
        setSeats((previous) =>
          sameSeats(previous, result.seats) ? previous : result.seats,
        );
        setFetchError(false);
        setLastChecked(new Date());
      } catch {
        if (!active()) return;
        clearSelection();
        setFetchError(true);
      } finally {
        inFlight = false;
        if (active()) {
          setLoading(false);
          if (!document.hidden) timer = setTimeout(refresh, POLL_MS);
        }
      }
    };
    const visibility = () => {
      clearTimeout(timer);
      if (!document.hidden) void refresh();
    };
    void refresh();
    document.addEventListener('visibilitychange', visibility);
    return () => {
      stopped = true;
      clearTimeout(timer);
      requestRef.current?.abort();
      document.removeEventListener('visibilitychange', visibility);
    };
  }, [date, user, reload, submitting]);

  function changeContext(nextDate: string, nextUser: DemoUser) {
    if (busyRef.current || (date === nextDate && user === nextUser)) return;
    ++generation.current;
    requestRef.current?.abort();
    clearSelection();
    setSeats([]);
    setLastChecked(null);
    setNotice(null);
    setFetchError(false);
    setLoading(validDate(nextDate));
    setDate(nextDate);
    setUser(nextUser);
  }

  const selectedSeat =
    seats.find((s) => s.seatNumber === selectedNumber) ?? null;
  const past = validDate(date) && date < today;
  const canSelect =
    validDate(date) && !past && !loading && !submitting && !fetchError;

  function selectSeat(seat: Seat) {
    if (
      !canSelect ||
      busyRef.current ||
      date < jaToday() ||
      seat.status === 'OCCUPIED'
    )
      return;
    const next =
      selectedRef.current?.seatNumber === seat.seatNumber ? null : seat;
    selectedRef.current = next;
    setSelectedNumber(next?.seatNumber ?? null);
  }

  async function execute(action: 'reserve' | 'cancel') {
    const seat = selectedRef.current;
    if (!canSelect || !seat || busyRef.current) return;
    if (date < jaToday()) {
      setToday(jaToday());
      clearSelection();
      return;
    }
    if (
      action === 'reserve'
        ? seat.status !== 'AVAILABLE'
        : seat.status !== 'MINE'
    )
      return;
    busyRef.current = true;
    ++generation.current;
    requestRef.current?.abort();
    setSubmitting(true);
    setNotice(null);
    try {
      if (action === 'reserve') await reserveSeat(date, seat.seatNumber, user);
      else if (seat.status === 'MINE')
        await cancelReservation(seat.reservationId, user);
      if (!mounted.current) return;
      setNotice({
        kind: 'success',
        text: `座席 ${String(seat.seatNumber).padStart(2, '0')} の予約${action === 'reserve' ? 'が完了しました。' : 'をキャンセルしました。'}`,
      });
    } catch (error) {
      if (!mounted.current) return;
      let text =
        '操作の結果を確認できません。最新の座席情報を確認してから操作してください。';
      if (error instanceof ApiError && error.status < 500) {
        text =
          error.status === 409
            ? 'この座席は先に予約されました。別の空席を選択してください。'
            : error.status === 404
              ? '対象の予約は見つかりません。最新の座席情報を確認してください。'
              : error.message;
      }
      setNotice({ kind: 'error', text });
    } finally {
      busyRef.current = false;
      if (mounted.current) {
        clearSelection();
        setLoading(true);
        setSubmitting(false);
        setReload((n) => n + 1);
      }
    }
  }

  function retry() {
    if (busyRef.current || loading || !validDate(date)) return;
    setLoading(true);
    setReload((n) => n + 1);
  }

  return {
    date,
    user,
    seats,
    selectedSeat,
    loading,
    submitting,
    fetchError,
    lastChecked,
    notice,
    past,
    canSelect,
    changeDate: (value: string) => changeContext(value, user),
    changeUser: (value: DemoUser) => changeContext(date, value),
    selectSeat,
    reserve: () => execute('reserve'),
    cancel: () => execute('cancel'),
    retry,
  };
}
