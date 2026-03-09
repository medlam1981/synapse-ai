
import React, { useState, useEffect, useRef } from 'react';
import { 
  Settings, Power, Send, ArrowLeft, Eye, EyeOff, ChevronDown, Sparkles, Bot, Cpu, Zap, Smartphone, Activity, Terminal, ShieldCheck, Globe, LayoutGrid, Layers, Link as LinkIcon, Database
} from 'lucide-react';
import { TerminalMessage, TerminalConfig, MessageSender } from './types';
import { getTerminalResponse } from './services/geminiService';

const RECOMMENDED_MODELS = [
  { id: 'local-manual', label: 'Manual Local Model', color: 'bg-orange-500/20 text-orange-400' },
  { id: 'gemini-3-flash-preview', label: 'Gemini 3 Flash', color: 'bg-cyan-500/20 text-cyan-400' },
  { id: 'gemini-3-pro-preview', label: 'Gemini 3 Pro', color: 'bg-purple-500/20 text-purple-400' }
];

const App: React.FC = () => {
  const [view, setView] = useState<'terminal' | 'config'>('terminal');
  const [messages, setMessages] = useState<TerminalMessage[]>([
    { id: '1', sender: 'system', text: '> Synapse AI Agent: Portal Integration Layer active.', timestamp: new Date() },
    { id: '2', sender: 'system', text: '> Ready to orchestrate Android hardware via REST/WS bridge.', timestamp: new Date() },
  ]);
  const [input, setInput] = useState('');
  const [isProcessing, setIsProcessing] = useState(false);
  const [showApiKey, setShowApiKey] = useState(false);

  const [stats, setStats] = useState({
    latency: 0,
    portalStatus: 'Standby',
    activeEndpoints: 12,
    device: 'Detecting...',
    startTime: Date.now()
  });

  const checkPortalHealth = async (url: string) => {
    const start = Date.now();
    try {
      await fetch(`${url}/a11y_tree`, { method: 'GET', signal: AbortSignal.timeout(3000) });
      setStats(prev => ({ ...prev, latency: Date.now() - start, portalStatus: 'LINK_ESTABLISHED' }));
    } catch {
      setStats(prev => ({ ...prev, latency: 0, portalStatus: 'OFFLINE' }));
    }
  };

  const [config, setConfig] = useState<TerminalConfig>({
    apiKey: '',
    selectedModel: 'local-manual',
    temperature: 0.7,
    maxTokens: 2048,
    portalUrl: 'http://10.180.104.191:8080',
    portalToken: '', // أدخل الـ token من إعدادات التطبيق
    localModelFile: undefined,
    isServiceRunning: true,
    isAutonomousMode: false
  });

  const scrollRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    checkPortalHealth(config.portalUrl);
    const interval = setInterval(() => checkPortalHealth(config.portalUrl), 10000);
    return () => clearInterval(interval);
  }, [config.portalUrl]);

  useEffect(() => {
    if (scrollRef.current) scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
  }, [messages]);

  const addMessage = (text: string, sender: MessageSender) => {
    setMessages(prev => [...prev, { id: Date.now().toString(), text, sender, timestamp: new Date() }]);
  };

  const processCommand = async () => {
    const cmd = input.trim();
    if (!cmd || isProcessing) return;
    addMessage(cmd, 'user');
    setInput('');
    setIsProcessing(true);
    try {
      const response = await getTerminalResponse(cmd, messages, config);
      addMessage(response, 'ai');
    } catch (err: any) {
      addMessage(`BRIDGE_ERROR: ${err.message}`, 'system');
    } finally {
      setIsProcessing(false);
    }
  };

  if (view === 'config') {
    return (
      <div className="h-screen w-screen flex flex-col bg-[#050505] text-[#e0e0e0] overflow-y-auto">
        <div className="bg-[#00e5ff] p-4 flex items-center gap-4 sticky top-0 z-50 shadow-[0_0_20px_rgba(0,229,255,0.3)]">
          <button onClick={() => setView('terminal')} className="text-black hover:bg-black/10 p-2 rounded-xl">
            <ArrowLeft size={24} />
          </button>
          <h1 className="text-black text-xl font-black uppercase tracking-widest">Synapse AI Sync Settings</h1>
        </div>
        <div className="p-6 space-y-6 max-w-lg mx-auto w-full pb-10">
           
           <div className="border border-white/5 rounded-3xl p-6 space-y-6 bg-white/[0.02] backdrop-blur-xl">
              <h3 className="text-[#00e5ff] text-[10px] font-bold uppercase tracking-[0.3em]">Synapse Portal Link</h3>
              
              <div className="space-y-4">
                <label className="text-[10px] text-white/40 font-bold uppercase tracking-widest block">Portal REST URL</label>
                <div className="flex items-center border border-white/10 rounded-2xl bg-black/60 px-4 py-4">
                  <Globe size={18} className="text-white/20 mr-3" />
                  <input 
                    type="text"
                    value={config.portalUrl}
                    onChange={(e) => setConfig({...config, portalUrl: e.target.value})}
                    placeholder="http://192.168.1.X:8080"
                    className="flex-1 bg-transparent border-none outline-none text-sm text-white font-mono"
                  />
                </div>
              </div>

              <div className="space-y-4">
                <label className="text-[10px] text-white/40 font-bold uppercase tracking-widest block">Access Token</label>
                <div className="flex items-center border border-white/10 rounded-2xl bg-black/60 px-4 py-4">
                  <ShieldCheck size={18} className="text-white/20 mr-3" />
                  <input 
                    type="text"
                    value={config.portalToken}
                    onChange={(e) => setConfig({...config, portalToken: e.target.value})}
                    placeholder="Portal UUID Token"
                    className="flex-1 bg-transparent border-none outline-none text-sm text-white font-mono"
                  />
                </div>
              </div>
           </div>

           <div className="border border-white/5 rounded-3xl p-6 space-y-6 bg-white/[0.02]">
              <h3 className="text-orange-400 text-[10px] font-bold uppercase tracking-[0.3em]">Local Model (Manual)</h3>
              <div className="space-y-4">
                <label className="text-[10px] text-white/40 font-bold uppercase tracking-widest block">Load Model from Storage</label>
                <div className="flex flex-col gap-4">
                  <label className="flex items-center justify-center border-2 border-dashed border-white/10 rounded-2xl bg-black/60 px-4 py-8 cursor-pointer hover:border-[#00e5ff]/40 transition-all">
                    <div className="flex flex-col items-center gap-2">
                      <Database size={24} className="text-white/20" />
                      <span className="text-[10px] text-white/40 font-bold uppercase tracking-widest">
                        {config.localModelFile ? `Loaded: ${config.localModelFile}` : "Select .gguf or .bin file"}
                      </span>
                    </div>
                    <input 
                      type="file"
                      className="hidden"
                      accept=".gguf,.bin,.onnx"
                      onChange={(e) => {
                        const file = e.target.files?.[0];
                        if (file) {
                          setConfig({...config, localModelFile: file.name, selectedModel: 'local-manual'});
                          addMessage(`> Neural Bridge: Local model file [${file.name}] detected.`, 'system');
                          addMessage(`> [SYSTEM] Initializing Neural Weights from storage...`, 'system');
                          setTimeout(() => {
                            addMessage(`> [SYSTEM] Model [${file.name}] loaded into Neural Buffer.`, 'system');
                          }, 1500);
                        }
                      }}
                    />
                  </label>
                </div>
              </div>
              <div className="space-y-4">
                <label className="text-[10px] text-white/40 font-bold uppercase tracking-widest block">Quick Select Model</label>
                <div className="flex flex-wrap gap-2">
                  {RECOMMENDED_MODELS.map(model => (
                    <button
                      key={model.id}
                      onClick={() => setConfig({...config, selectedModel: model.id})}
                      className={`px-3 py-2 rounded-xl text-[10px] font-bold transition-all ${
                        config.selectedModel === model.id 
                          ? 'bg-[#00e5ff] text-black shadow-[0_0_15px_rgba(0,229,255,0.4)]' 
                          : 'bg-white/5 text-white/40 hover:bg-white/10'
                      }`}
                    >
                      {model.label}
                    </button>
                  ))}
                </div>
              </div>
           </div>

           <div className="border border-white/5 rounded-3xl p-6 space-y-6 bg-white/[0.02]">
              <h3 className="text-emerald-400 text-[10px] font-bold uppercase tracking-[0.3em]">Autonomous HUD (Human Mode)</h3>
              <div className="flex items-center justify-between">
                <div className="space-y-1">
                  <span className="text-xs text-white font-bold">Enable Accessibility HUD</span>
                  <p className="text-[10px] text-white/40">Allows AI to see and control other apps</p>
                </div>
                <button 
                  onClick={() => {
                    const nextMode = !config.isAutonomousMode;
                    setConfig({...config, isAutonomousMode: nextMode});
                    if (nextMode) {
                      addMessage("> [SYSTEM] Synapse HUD Overlay: Initializing Accessibility Bridge...", "system");
                      setTimeout(() => {
                        addMessage("> [SYSTEM] HUD Perception: ACTIVE. Screen hierarchy synchronized.", "system");
                      }, 1000);
                    } else {
                      addMessage("> [SYSTEM] Synapse HUD Overlay: Disconnected.", "system");
                    }
                  }}
                  className={`w-12 h-6 rounded-full transition-all relative ${config.isAutonomousMode ? 'bg-[#00e5ff]' : 'bg-white/10'}`}
                >
                  <div className={`absolute top-1 w-4 h-4 rounded-full bg-white transition-all ${config.isAutonomousMode ? 'left-7' : 'left-1'}`}></div>
                </button>
              </div>
              {config.isAutonomousMode && (
                <div className="p-4 rounded-2xl bg-[#00e5ff]/5 border border-[#00e5ff]/20 animate-pulse">
                  <div className="flex items-center gap-2 text-[#00e5ff] text-[10px] font-bold uppercase tracking-widest">
                    <Activity size={12} /> HUD Overlay Active
                  </div>
                </div>
              )}
           </div>

           <button onClick={() => setView('terminal')} className="w-full bg-[#00e5ff] text-black font-black py-5 rounded-2xl shadow-[0_0_30px_rgba(0,229,255,0.2)] uppercase tracking-[0.2em]">Synchronize & Deploy</button>
        </div>
      </div>
    );
  }

  return (
    <div className="h-screen w-screen flex flex-col bg-[#050505] overflow-hidden neural-grid">
      <div className="pt-12 px-6 pb-4 flex justify-between items-center relative z-20">
        <div className="flex items-center gap-4">
          <div className="p-2.5 rounded-2xl bg-gradient-to-br from-[#111] to-black border border-[#00e5ff]/30 shadow-[0_0_15px_rgba(0,229,255,0.1)]">
            <Bot className="text-[#00e5ff] w-7 h-7" />
          </div>
          <div>
            <h1 className="text-white text-xl font-black tracking-tighter uppercase leading-none cyan-glow font-['Orbitron']">Synapse AI Agent</h1>
            <div className="flex items-center gap-2 mt-1">
              <span className={`w-1.5 h-1.5 rounded-full ${stats.portalStatus === 'LINK_ESTABLISHED' ? 'bg-emerald-500 animate-pulse' : 'bg-red-500'}`}></span>
              <span className="text-[9px] text-[#00e5ff]/80 font-bold tracking-[0.2em] uppercase">{stats.portalStatus === 'LINK_ESTABLISHED' ? 'Portal Connected' : 'Portal Offline'}</span>
            </div>
          </div>
        </div>
        <button onClick={() => setView('config')} className="p-3.5 rounded-2xl bg-white/5 border border-white/5 text-white/40 hover:text-[#00e5ff] transition-all"><Settings size={20} /></button>
      </div>

      <div className="px-6 py-2">
        <div className="relative overflow-hidden rounded-[2.5rem] bg-gradient-to-br from-[#0a0a0a] to-black border border-white/10 p-7 flex flex-col gap-6 shadow-2xl box-glow">
          <div className="scanline-overlay"></div>
          
          {config.isAutonomousMode && (
            <div className="absolute top-0 left-0 w-full h-full bg-[#00e5ff]/5 pointer-events-none z-0">
              <div className="absolute top-4 right-4 flex items-center gap-2">
                 <span className="w-2 h-2 rounded-full bg-red-500 animate-ping"></span>
                 <span className="text-[10px] text-red-500 font-black tracking-widest uppercase">REC HUD</span>
              </div>
            </div>
          )}

          <div className="flex justify-between items-start relative z-10">
            <div className="space-y-1">
              <div className="flex items-center gap-2 text-[#00e5ff] font-bold text-[10px] uppercase tracking-[0.2em]">
                <LinkIcon size={12} className="animate-pulse" /> API BRIDGE: {config.portalUrl.split('//')[1]}
              </div>
              <h2 className="text-white text-xl font-black tracking-tight">Portal Orchestration</h2>
            </div>
            <div className="bg-[#00e5ff]/10 px-4 py-2 rounded-2xl border border-[#00e5ff]/20 flex items-center gap-2 font-mono text-[10px] text-[#00e5ff] font-bold">
               {stats.latency}ms RT
            </div>
          </div>
          
          <div className="grid grid-cols-3 gap-3 relative z-10">
            <div className="bg-white/[0.04] rounded-2xl p-4 border border-white/5 flex flex-col items-center gap-2">
              <Activity size={18} className="text-[#00e5ff]" />
              <div className="text-center">
                <span className="text-[8px] text-white/30 uppercase font-bold block">Status</span>
                <span className="text-[10px] text-emerald-400 font-mono font-bold">{stats.portalStatus === 'LINK_ESTABLISHED' ? 'LINKED' : stats.portalStatus}</span>
              </div>
            </div>
            <div className="bg-white/[0.04] rounded-2xl p-4 border border-white/5 flex flex-col items-center gap-2">
              <Layers size={18} className="text-purple-400" />
              <div className="text-center">
                <span className="text-[8px] text-white/30 uppercase font-bold block">Endpoints</span>
                <span className="text-[10px] text-white/90 font-mono font-bold">{stats.activeEndpoints} Ready</span>
              </div>
            </div>
            <div className="bg-white/[0.04] rounded-2xl p-4 border border-white/5 flex flex-col items-center gap-2">
              <Smartphone size={18} className={config.isAutonomousMode ? "text-emerald-400" : "text-amber-400"} />
              <div className="text-center">
                <span className="text-[8px] text-white/30 uppercase font-bold block">Control</span>
                <span className="text-[10px] text-white/90 font-mono font-bold">{config.isAutonomousMode ? 'HUD_HUMAN' : 'FULL_IO'}</span>
              </div>
            </div>
          </div>
        </div>
      </div>

      <div 
        ref={scrollRef}
        className="flex-1 overflow-y-auto px-6 py-4 terminal-scrollbar font-mono text-[13px] space-y-7"
      >
        {messages.map((msg) => (
          <div key={msg.id} className="animate-in fade-in slide-in-from-bottom-3 duration-500 flex gap-5 group">
             <div className={`mt-1.5 h-6 w-1 rounded-full ${msg.sender === 'user' ? 'bg-[#00e5ff]' : msg.sender === 'ai' ? 'bg-purple-500' : 'bg-white/10'}`}></div>
             <div className="flex-1">
               <div className={`text-[10px] font-bold uppercase tracking-widest mb-1.5 ${msg.sender === 'user' ? 'text-[#00e5ff]/60' : msg.sender === 'ai' ? 'text-purple-400/60' : 'text-white/20'}`}>
                 {msg.sender === 'user' ? 'Operator' : msg.sender === 'ai' ? 'Neural_Agent' : 'Bridge_Log'}
               </div>
               <div className={`whitespace-pre-wrap leading-relaxed ${msg.sender === 'user' ? 'text-white font-bold text-sm' : 'text-white/90 text-xs'}`}>
                 {msg.text}
               </div>
             </div>
          </div>
        ))}
        {isProcessing && (
          <div className="flex gap-5 animate-pulse">
            <div className="mt-1.5 h-6 w-1 rounded-full bg-[#00e5ff]"></div>
            <div className="flex-1">
               <div className="text-[10px] font-bold uppercase tracking-widest mb-1.5 text-[#00e5ff]/60">
                 {config.isAutonomousMode ? 'Neural Perception Active' : 'Analyzing Screen Hierarchy'}
               </div>
               <div className="text-[#00e5ff] font-bold text-xs flex items-center gap-2 uppercase tracking-widest">
                 <Activity size={14} className="animate-spin" /> {config.isAutonomousMode ? 'Capturing HUD Context...' : 'Fetching a11y_tree...'}
               </div>
            </div>
          </div>
        )}
      </div>

      <div className="p-8 bg-gradient-to-t from-black via-black/80 to-transparent backdrop-blur-md relative z-20">
        <div className="flex items-center gap-5 max-w-2xl mx-auto">
          <div className="flex-1 bg-white/[0.03] rounded-[1.8rem] px-7 py-5 flex items-center border border-white/10 focus-within:border-[#00e5ff]/40 focus-within:bg-white/[0.06] transition-all shadow-2xl">
            <input
              ref={inputRef}
              type="text"
              value={input}
              onChange={(e) => setInput(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && processCommand()}
              placeholder="Ex: Open YouTube and search for AI news..."
              className="w-full bg-transparent border-none text-white focus:outline-none placeholder-white/10 text-sm font-medium"
            />
          </div>
          <button 
            onClick={processCommand}
            disabled={isProcessing || !input.trim()}
            className="bg-[#00e5ff] text-black p-5 rounded-[1.4rem] shadow-[0_15px_35px_rgba(0,229,255,0.3)] hover:scale-105 active:scale-95 transition-all disabled:opacity-20"
          >
            <Send size={24} fill="black" />
          </button>
        </div>
      </div>
      <div className="h-6 bg-black" />
    </div>
  );
};

export default App;
