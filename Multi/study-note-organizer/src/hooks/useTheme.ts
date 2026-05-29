import { useEffect } from 'react';
import type { AppSettings } from '../types';

export function applyTheme(theme: AppSettings['theme']): void {
  const html = document.documentElement;
  if (theme === 'dark') {
    html.classList.add('dark');
  } else {
    html.classList.remove('dark');
  }
}

export function getSystemTheme(): AppSettings['theme'] {
  return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
}

export function useThemeEffect(theme: AppSettings['theme']): void {
  useEffect(() => {
    applyTheme(theme);
  }, [theme]);
}
