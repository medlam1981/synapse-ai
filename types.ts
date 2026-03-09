
export type MessageSender = 'user' | 'ai' | 'system';

export interface TerminalMessage {
  id: string;
  sender: MessageSender;
  text: string;
  timestamp: Date;
  model?: string;
}

export interface TerminalConfig {
  apiKey: string;
  selectedModel: string;
  temperature: number;
  maxTokens: number;
  portalUrl: string;
  portalToken: string; // Synapse Portal التوكن الخاص بـ
  localModelFile?: string; // Name of the manually loaded model file
  isServiceRunning: boolean;
  isAutonomousMode: boolean;
}

export type RemoteButton = string | number;
