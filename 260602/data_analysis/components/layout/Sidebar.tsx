'use client';

import { useState } from 'react';
import { Upload, BarChart2, ChevronLeft, ChevronRight } from 'lucide-react';
import { cn } from '@/lib/utils';
import { useDataStore } from '@/store/dataStore';
import type { MenuItem, MenuView } from '@/lib/types';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';

const MENU_ITEMS: MenuItem[] = [
  { id: 'upload', label: '파일 업로드', icon: 'Upload', requiresData: false },
  { id: 'analysis', label: '기본 데이터 분석', icon: 'BarChart2', requiresData: true },
];

const ICON_MAP: Record<string, React.ElementType> = {
  Upload,
  BarChart2,
};

export function Sidebar() {
  const [collapsed, setCollapsed] = useState(false);
  const { data, activeView, setActiveView } = useDataStore();

  return (
    <aside
      className={cn(
        'relative flex flex-col border-r bg-gray-50 transition-all duration-200 shrink-0',
        collapsed ? 'w-14' : 'w-52'
      )}
    >
      <nav className="flex flex-col gap-1 p-2 flex-1">
        {MENU_ITEMS.map((item) => {
          const Icon = ICON_MAP[item.icon];
          const isDisabled = item.requiresData && !data;
          const isActive = activeView === item.id;

          const button = (
            <button
              key={item.id}
              onClick={() => !isDisabled && setActiveView(item.id)}
              disabled={isDisabled}
              className={cn(
                'flex items-center gap-2.5 w-full rounded-md px-2.5 py-2 text-sm font-medium transition-colors',
                isActive && !isDisabled
                  ? 'bg-blue-600 text-white'
                  : 'text-gray-700 hover:bg-gray-200',
                isDisabled && 'opacity-40 cursor-not-allowed hover:bg-transparent'
              )}
            >
              <Icon className="w-4 h-4 shrink-0" />
              {!collapsed && <span className="truncate">{item.label}</span>}
            </button>
          );

          if (collapsed) {
            return (
              <Tooltip key={item.id}>
                <TooltipTrigger asChild>{button}</TooltipTrigger>
                <TooltipContent side="right">
                  <p>{item.label}{isDisabled ? ' (파일 업로드 필요)' : ''}</p>
                </TooltipContent>
              </Tooltip>
            );
          }

          return button;
        })}
      </nav>

      <button
        onClick={() => setCollapsed((v) => !v)}
        className="absolute -right-3 top-6 z-10 flex h-6 w-6 items-center justify-center rounded-full border bg-white shadow-sm hover:bg-gray-100"
      >
        {collapsed ? (
          <ChevronRight className="w-3.5 h-3.5 text-gray-600" />
        ) : (
          <ChevronLeft className="w-3.5 h-3.5 text-gray-600" />
        )}
      </button>
    </aside>
  );
}
