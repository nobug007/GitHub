const WEEKDAYS = ['일', '월', '화', '수', '목', '금', '토'];

export function getDateParts(dateStr: string): { month: number; day: number; weekday: string } {
  const date = new Date(dateStr + 'T00:00:00');
  return {
    month: date.getMonth() + 1,
    day: date.getDate(),
    weekday: WEEKDAYS[date.getDay()],
  };
}

export function formatDate(dateStr: string): string {
  const { month, day, weekday } = getDateParts(dateStr);
  return `${month}월 ${day}일(${weekday})`;
}

export function toYYMMDD(dateStr: string): string {
  const date = new Date(dateStr + 'T00:00:00');
  const yy = String(date.getFullYear()).slice(2);
  const mm = String(date.getMonth() + 1).padStart(2, '0');
  const dd = String(date.getDate()).padStart(2, '0');
  return `${yy}${mm}${dd}`;
}

export function todayISO(): string {
  const now = new Date();
  const y = now.getFullYear();
  const m = String(now.getMonth() + 1).padStart(2, '0');
  const d = String(now.getDate()).padStart(2, '0');
  return `${y}-${m}-${d}`;
}

export function extractKeywords(content: string): [string, string] {
  const match = content.match(/\[핵심 키워드\]\s*\n([^\n,，]+)[,，\s]+([^\n,，]+)/);
  if (match) {
    const k1 = match[1].trim().replace(/[^\w가-힣]/g, '');
    const k2 = match[2].trim().replace(/[^\w가-힣]/g, '');
    return [k1 || '정리', k2 || '수업'];
  }
  return ['정리', '수업'];
}

export function removeKeywordLine(content: string): string {
  return content.replace(/\n?\[핵심 키워드\]\s*\n[^\n]+/g, '').trim();
}

export function generateId(): string {
  return `${Date.now()}-${Math.random().toString(36).slice(2, 9)}`;
}
