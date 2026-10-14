import { useEffect, useRef } from 'react';
import { t } from './strings';

interface Props {
  title: string;
  text: string;
  confirmLabel: string;
  busy: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}

/** Small modal on top of the editor for destructive actions. */
export function ConfirmDialog({ title, text, confirmLabel, busy, onConfirm, onCancel }: Props) {
  const cancelRef = useRef<HTMLButtonElement>(null);

  useEffect(() => cancelRef.current?.focus(), []);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return;
      e.stopImmediatePropagation();
      onCancel();
    };
    window.addEventListener('keydown', onKey, true);
    return () => window.removeEventListener('keydown', onKey, true);
  }, [onCancel]);

  return (
    <div
      className="overlay confirm-overlay"
      onMouseDown={(e) => e.target === e.currentTarget && onCancel()}
    >
      <div
        className="confirm-dialog"
        role="alertdialog"
        aria-modal="true"
        aria-labelledby="confirm-title"
      >
        <h2 id="confirm-title">{title}</h2>
        <p className="muted">{text}</p>
        <div className="confirm-buttons">
          <button ref={cancelRef} type="button" onClick={onCancel}>
            {t.cancel}
          </button>
          <button type="button" className="danger" disabled={busy} onClick={onConfirm}>
            {confirmLabel}
          </button>
        </div>
      </div>
    </div>
  );
}
