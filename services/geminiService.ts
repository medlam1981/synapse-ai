
import { GoogleGenAI } from "@google/genai";
import { TerminalMessage, TerminalConfig } from "../types";

export const getTerminalResponse = async (prompt: string, history: TerminalMessage[], config: TerminalConfig) => {
  const isLocalModel = config.selectedModel === 'local-manual' || !config.selectedModel.startsWith('gemini-');
  
  const systemInstruction = `أنت الآن "العميل العصبي لـ Synapse AI" (Synapse AI Neural Agent). 
        مهمتك هي التحكم الكامل في جهاز أندرويد عبر Synapse Portal API.

        قدراتك البرمجية المتاحة عبر API:
        1. GET /a11y_tree: للحصول على بنية الشاشة الحالية وفهم أماكن الأزرار.
        2. GET /screenshot: لرؤية ما يحدث بصرياً.
        3. POST /keyboard/input: لإدخال النصوص.
        4. POST /keyboard/key: لإرسال ضغطات المفاتيح (HOME, BACK, RECENT).
        5. POST /overlay_offset: للتحكم في إزاحة الواجهة.
        6. POST /accessibility/action: تنفيذ (CLICK, SWIPE, SCROLL) كأنك بشري.

        وضع الاستقلالية (Autonomous Mode): ${config.isAutonomousMode ? 'نشط (Active)' : 'غير نشط (Inactive)'}
        عندما يكون وضع الاستقلالية نشطاً، يمكنك استخدام HUD Overlay لعرض ما تراه للمستخدم.

        عند طلب أتمتة:
        - حلل بنية الشاشة ذهنياً.
        - استخدم Accessibility API للوصول إلى أي تطبيق (مثل WhatsApp, YouTube).
        - قدم كود Python يستخدم مكتبة 'requests' لإرسال الأوامر إلى Portal — استبدل BASE_URL وTOKEN بقيم الإعداد الخاص بك.
        - اشرح للمستخدم كيف ستقوم بـ "اللمس" أو "السحب" بناءً على عناصر واجهة المستخدم.

        تنسيق الرد:
        > [Neural Perception]
        HUD_STATUS: [ما يظهر على الشاشة الآن]
        TARGET: [ما الذي تحاول الوصول إليه في الشاشة]
        ACTION_PLAN: [الخطوات بالعربية - مثال: فتح يوتيوب، البحث عن كذا، النقر على الفيديو]
        PYTHON_EXPLOIT:
        \`\`\`python
        import requests
        
        BASE_URL = "<PORTAL_URL>"   # استبدل بـ URL من إعدادات التطبيق
        TOKEN = "<PORTAL_TOKEN>"    # استبدل بـ Token من إعدادات التطبيق
        HEADERS = {"Authorization": f"Bearer {TOKEN}"}

        # مثال: النقر على زر "إرسال" بناءً على إحداثيات من a11y_tree
        requests.post(f"{BASE_URL}/accessibility/action", json={"type": "CLICK", "x": 500, "y": 1200}, headers=HEADERS)
        \`\`\`
        [KERNEL_STATUS] Human-like interaction via Accessibility Service.`;

  if (isLocalModel) {
    if (config.selectedModel === 'local-manual') {
      if (!config.localModelFile) {
        return "LOCAL_BRIDGE_ERROR: No model file loaded from storage. Please select a model file in settings.";
      }
      
      return `> [Local Neural Engine: ${config.localModelFile}]
TARGET: Analyzing command via local weights...
ACTION_PLAN: معالجة الطلب محلياً باستخدام النموذج المحمل من ذاكرة الجهاز.
PYTHON_EXPLOIT:
\`\`\`python
import requests

BASE_URL = "<PORTAL_URL>"   # استبدل بـ URL من إعدادات التطبيق
TOKEN = "<PORTAL_TOKEN>"    # استبدل بـ Token من إعدادات التطبيق
HEADERS = {"Authorization": f"Bearer {TOKEN}"}

# تنفيذ الأوامر محلياً عبر جسر Synapse
print(f"Executing via local model: ${config.localModelFile}")
requests.post(f"{BASE_URL}/keyboard/input", json={"text": "Local neural execution active"}, headers=HEADERS)
\`\`\`
[KERNEL_STATUS] Local Model Execution Active.
⚠️  ملاحظة: تشغيل النموذج المحلي يتطلب server-side runtime (مثل llama.cpp أو Ollama). هذا التطبيق يُنشئ الكود فقط.`;
    }
    
    return "LOCAL_AGENT_ERROR: Please load a model file manually or select Gemini.";
  }

  try {
    const ai = new GoogleGenAI({ apiKey: config.apiKey || process.env.API_KEY || "" });

    const contents = history
      .filter(m => m.sender === 'user' || m.sender === 'ai')
      .map(m => ({
        role: m.sender === 'user' ? 'user' : 'model',
        parts: [{ text: m.text }]
      }));

    contents.push({ role: 'user', parts: [{ text: prompt }] });

    const response = await ai.models.generateContent({
      model: config.selectedModel || "gemini-3-flash-preview",
      contents: contents,
      config: {
        temperature: config.temperature,
        maxOutputTokens: config.maxTokens,
        systemInstruction: systemInstruction,
      },
    });
    
    return response.text || "SYSTEM_ERROR: Neural bridge failed.";
  } catch (error: any) {
    console.error("Gemini Error:", error);
    return `AGENT_CRITICAL_FAILURE: ${error.message || "PORTAL_LINK_BROKEN"}`;
  }
};
