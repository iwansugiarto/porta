import { useState, useEffect, useRef, useCallback } from "react";

/** Persist draft annotations per cascadeId + stepIndex across re-renders and reloads. */
const ANNOTATIONS_PREFIX = "porta:annotations:";

const annotationStore = {
  get(key: string): Record<number, string> {
    try {
      const raw = localStorage.getItem(ANNOTATIONS_PREFIX + key);
      if (!raw) return {};
      return JSON.parse(raw) as Record<number, string>;
    } catch {
      return {};
    }
  },
  set(key: string, value: Record<number, string>): void {
    try {
      const nonEmpty = Object.fromEntries(
        Object.entries(value).filter(([, v]) => v.trim() !== ""),
      );
      if (Object.keys(nonEmpty).length > 0) {
        localStorage.setItem(ANNOTATIONS_PREFIX + key, JSON.stringify(nonEmpty));
      } else {
        localStorage.removeItem(ANNOTATIONS_PREFIX + key);
      }
    } catch {
      // localStorage might be full or unavailable
    }
  },
  delete(key: string): void {
    try {
      localStorage.removeItem(ANNOTATIONS_PREFIX + key);
    } catch {}
  },
};

interface UseDraftAnnotationsResult {
  annotations: Record<number, string>;
  setAnnotation: (segIndex: number, text: string) => void;
  clearAnnotations: () => void;
}

/**
 * Manages per-plan-message annotations with localStorage persistence.
 * Each annotation maps a segment index to a comment string.
 */
export function useDraftAnnotations(
  cascadeId: string | null,
  stepIndex: number,
): UseDraftAnnotationsResult {
  const storageKey = cascadeId ? `${cascadeId}:${stepIndex}` : null;
  const [annotations, setAnnotations] = useState<Record<number, string>>({});
  const storageKeyRef = useRef(storageKey);

  useEffect(() => {
    // Persist current annotations before switching
    if (storageKeyRef.current) {
      annotationStore.set(storageKeyRef.current, annotations);
    }
    storageKeyRef.current = storageKey;
    setAnnotations(storageKey ? annotationStore.get(storageKey) : {});
  }, [storageKey]); // eslint-disable-line react-hooks/exhaustive-deps

  const setAnnotation = useCallback(
    (segIndex: number, text: string) => {
      setAnnotations((prev) => {
        const next = { ...prev };
        if (text.trim()) {
          next[segIndex] = text;
        } else {
          delete next[segIndex];
        }
        if (storageKey) annotationStore.set(storageKey, next);
        return next;
      });
    },
    [storageKey],
  );

  const clearAnnotations = useCallback(() => {
    setAnnotations({});
    if (storageKey) annotationStore.delete(storageKey);
  }, [storageKey]);

  return { annotations, setAnnotation, clearAnnotations };
}

export { annotationStore };
