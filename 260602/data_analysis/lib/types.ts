export interface ParsedData {
  columns: string[];
  rows: Record<string, unknown>[];
}

export type MenuView = 'upload' | 'analysis';

export interface MenuItem {
  id: MenuView;
  label: string;
  icon: string;
  requiresData: boolean;
}
