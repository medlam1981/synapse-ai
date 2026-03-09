
import React from 'react';
import { RemoteButton as RemoteButtonType } from '../types';

interface RemoteButtonProps {
  label: React.ReactNode;
  value: RemoteButtonType;
  onClick: (val: RemoteButtonType) => void;
  className?: string;
}

export const Button: React.FC<RemoteButtonProps> = ({ label, value, onClick, className = "" }) => {
  return (
    <button
      onClick={() => onClick(value)}
      className={`flex items-center justify-center rounded-lg transition-all duration-200 button-active shadow-md font-bold text-sm ${className}`}
    >
      {label}
    </button>
  );
};
