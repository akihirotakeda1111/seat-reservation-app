import { useEffect, useRef } from 'react';
import type { AdminReservation } from '../types/adminReservation';
import { formatDate } from '../utils/jaDate';
interface Props {
  date: string;
  reservation: AdminReservation;
  busy: boolean;
  onClose: () => void;
  onConfirm: () => void;
}
export function CancelReservationDialog({
  date,
  reservation,
  busy,
  onClose,
  onConfirm,
}: Props) {
  const ref = useRef<HTMLDialogElement>(null);
  const returnFocus = useRef(document.activeElement);
  useEffect(() => {
    const dialog = ref.current!;
    dialog.showModal();
    return () => {
      dialog.close();
      const target = returnFocus.current;
      if (target instanceof HTMLElement && target.isConnected) target.focus();
    };
  }, []);
  return (
    <dialog
      ref={ref}
      className="admin-dialog"
      aria-labelledby="cancel-title"
      aria-describedby="cancel-details"
      onCancel={(e) => {
        e.preventDefault();
        if (!busy) onClose();
      }}
    >
      <h2 id="cancel-title">予約をキャンセルしますか？</h2>
      <div id="cancel-details">
        <p>利用日：{formatDate(date)}</p>
        <p>座席番号：{String(reservation.seatNumber).padStart(2, '0')}</p>
        <p className="admin-user-id">予約者ID：{reservation.userId}</p>
      </div>
      {busy && <p role="status">キャンセル中…</p>}
      <div className="admin-actions">
        <button
          className="button button-secondary"
          autoFocus
          disabled={busy}
          onClick={onClose}
        >
          戻る
        </button>
        <button
          className="button button-danger"
          disabled={busy}
          onClick={onConfirm}
        >
          予約をキャンセル
        </button>
      </div>
    </dialog>
  );
}
