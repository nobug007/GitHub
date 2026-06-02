'use client';

import { TableIcon, Columns3, AlertTriangle, BarChart3 } from 'lucide-react';
import type { DatasetSummary } from '@/lib/analysis';

interface Props {
  summary: DatasetSummary;
}

interface CardData {
  label: string;
  value: string;
  sub?: string;
  icon: React.ElementType;
  iconClass: string;
  bgClass: string;
}

export function SummaryCards({ summary }: Props) {
  const cards: CardData[] = [
    {
      label: '전체 행 수',
      value: summary.rowCount.toLocaleString('ko-KR'),
      sub: '개 레코드',
      icon: TableIcon,
      iconClass: 'text-blue-600',
      bgClass: 'bg-blue-50',
    },
    {
      label: '전체 열 수',
      value: summary.columnCount.toLocaleString('ko-KR'),
      sub: '개 컬럼',
      icon: Columns3,
      iconClass: 'text-violet-600',
      bgClass: 'bg-violet-50',
    },
    {
      label: '전체 결측치',
      value: summary.totalMissing.toLocaleString('ko-KR'),
      sub: '개 셀',
      icon: AlertTriangle,
      iconClass: summary.totalMissing > 0 ? 'text-amber-600' : 'text-gray-400',
      bgClass: summary.totalMissing > 0 ? 'bg-amber-50' : 'bg-gray-50',
    },
    {
      label: '결측치 비율',
      value: (summary.missingRatio * 100).toFixed(2) + '%',
      sub: '전체 셀 기준',
      icon: BarChart3,
      iconClass: summary.missingRatio > 0.1 ? 'text-red-500' : 'text-emerald-600',
      bgClass: summary.missingRatio > 0.1 ? 'bg-red-50' : 'bg-emerald-50',
    },
  ];

  return (
    <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
      {cards.map((card) => {
        const Icon = card.icon;
        return (
          <div
            key={card.label}
            className="rounded-xl border bg-white p-5 shadow-sm flex items-start gap-4"
          >
            <div className={`rounded-lg p-2 ${card.bgClass}`}>
              <Icon className={`w-5 h-5 ${card.iconClass}`} />
            </div>
            <div>
              <p className="text-xs text-gray-500 mb-0.5">{card.label}</p>
              <p className="text-2xl font-bold text-gray-800 leading-none">{card.value}</p>
              {card.sub && <p className="text-xs text-gray-400 mt-1">{card.sub}</p>}
            </div>
          </div>
        );
      })}
    </div>
  );
}
