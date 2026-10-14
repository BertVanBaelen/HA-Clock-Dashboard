import { mdiCheckCircle } from '@mdi/js';
import { useCallback, useEffect, useRef, useState } from 'react';
import { Icon } from './Icon';

const SHOW_MS = 2600;
const LEAVE_MS = 200;

interface ToastState {
  id: number;
  text: string;
  leaving: boolean;
}

/** Short confirmation at the bottom of the panel; a new message replaces the current one. */
export function useToast() {
  const [toast, setToast] = useState<ToastState | null>(null);
  const timers = useRef<number[]>([]);

  const clear = () => {
    timers.current.forEach((id) => window.clearTimeout(id));
    timers.current = [];
  };

  const notify = useCallback((text: string) => {
    clear();
    setToast({ id: Date.now(), text, leaving: false });
    timers.current.push(
      window.setTimeout(() => setToast((t) => (t ? { ...t, leaving: true } : t)), SHOW_MS),
      window.setTimeout(() => setToast(null), SHOW_MS + LEAVE_MS),
    );
  }, []);

  useEffect(() => clear, []);

  const element = toast ? (
    <div className="toast-host" role="status" aria-live="polite">
      <div key={toast.id} className={toast.leaving ? 'toast leaving' : 'toast'}>
        <Icon path={mdiCheckCircle} size={20} />
        <span>{toast.text}</span>
      </div>
    </div>
  ) : null;

  return { notify, element };
}
