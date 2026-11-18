import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from './api';

export const LIVE_INTERVAL_MS = 5000;

/** Loads one or more API paths together, and again every few seconds while `live` is on. Keeps the last good data on a failed refresh. */
export function useLive<T>(paths: string[], live: boolean): { data: T | null; error: Error | null; reload: () => Promise<void> } {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<Error | null>(null);
  const key = paths.join('|');
  const alive = useRef(true);

  const reload = useCallback(async () => {
    try {
      const results = await Promise.all(key.split('|').map((p) => api<unknown>(p)));
      if (!alive.current) return;
      setData((results.length === 1 ? results[0] : results) as T);
      setError(null);
    } catch (e) {
      if (alive.current) setError(e as Error);
    }
  }, [key]);

  useEffect(() => {
    alive.current = true;
    void reload();
    if (!live) return () => { alive.current = false; };
    const timer = window.setInterval(() => void reload(), LIVE_INTERVAL_MS);
    return () => { alive.current = false; window.clearInterval(timer); };
  }, [reload, live]);

  return { data, error, reload };
}
