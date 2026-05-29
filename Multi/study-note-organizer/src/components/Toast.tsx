import { useEffect, useState } from 'react';
import { CheckCircle, AlertTriangle } from 'lucide-react';

export interface ToastMessage {
  id: string;
  text: string;
  type: 'success' | 'warning' | 'error';
}

interface Props {
  messages: ToastMessage[];
  onRemove: (id: string) => void;
}

export default function Toast({ messages, onRemove }: Props) {
  return (
    <div className="fixed bottom-6 right-6 z-50 flex flex-col gap-2">
      {messages.map((m) => (
        <ToastItem key={m.id} message={m} onRemove={onRemove} />
      ))}
    </div>
  );
}

function ToastItem({ message, onRemove }: { message: ToastMessage; onRemove: (id: string) => void }) {
  const [visible, setVisible] = useState(false);

  useEffect(() => {
    setVisible(true);
    const t = setTimeout(() => {
      setVisible(false);
      setTimeout(() => onRemove(message.id), 300);
    }, 2700);
    return () => clearTimeout(t);
  }, [message.id, onRemove]);

  const colors = {
    success: 'bg-green-600 text-white',
    warning: 'bg-yellow-500 text-white',
    error: 'bg-red-600 text-white',
  };

  const Icon = message.type === 'success' ? CheckCircle : AlertTriangle;

  return (
    <div
      className={`flex items-center gap-2 rounded-lg px-4 py-3 shadow-lg transition-all duration-300 ${colors[message.type]} ${visible ? 'opacity-100 translate-y-0' : 'opacity-0 translate-y-2'}`}
    >
      <Icon className="h-4 w-4 shrink-0" />
      <span className="text-sm font-medium">{message.text}</span>
    </div>
  );
}
