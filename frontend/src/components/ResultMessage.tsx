import type { ResultNotice } from '../types/reservation';

interface Props {
  notice: ResultNotice | null;
  fetchError: boolean;
  loading: boolean;
  onRetry: () => void;
  emptyText?: string;
  fetchErrorText?: string;
}

export function ResultMessage({
  notice,
  fetchError,
  loading,
  onRetry,
  emptyText = '日付と座席を選択してください。',
  fetchErrorText = '最新の座席情報を取得できません。',
}: Props) {
  return (
    <div className="messages">
      <div
        className="message"
        data-kind={notice?.kind ?? 'info'}
        role="status"
        aria-label="操作結果"
        aria-live="polite"
      >
        {notice?.text ?? emptyText}
      </div>
      {fetchError && (
        <div className="message fetch-error" data-kind="error" role="alert">
          <span>{fetchErrorText}</span>
          <button
            type="button"
            className="button button-secondary"
            onClick={onRetry}
            disabled={loading}
          >
            {loading ? '再取得中…' : '再取得'}
          </button>
        </div>
      )}
    </div>
  );
}
