export type KidQuestion = {
  id: string;
  question: string;
  why: string;
  answer_type: string;
};

export type KidPlanStart = {
  session_id: string;
  date_label: string;
  weekday_label: string;
  run_mode?: "official" | "debug";
  business_date?: string;
  companion_message: string;
  questions: KidQuestion[];
  can_continue_without_answers: boolean;
};

export type KidQuestTask = {
  title: string;
  subject: string;
  minutes: number;
  completion_standard: string;
  quest_label: string;
  reward_stars: number;
};

export type KidPlanFinish = {
  session_id: string;
  date_label: string;
  weekday_label: string;
  run_mode?: "official" | "debug";
  business_date?: string;
  plan_title: string;
  total_minutes: number;
  tasks: KidQuestTask[];
  deferred_tasks: Array<{ title: string; reason: string; suggested_next_time?: string }>;
  child_message: string;
  parent_explanation: string;
  saved_plan_path?: string | null;
};

export type KidSettlement = {
  settlement_title: string;
  date_label: string;
  weekday_label: string;
  run_mode?: "official" | "debug";
  business_date?: string;
  effort_stars: number;
  completion_rate: number;
  completed_summary: string;
  stuck_points: string[];
  pending_summary: string;
  encouragement: string;
  closed_loop_status: string;
  saved_log_path?: string | null;
};

const API_BASE = import.meta.env.VITE_STUDYPILOT_API_BASE ?? "/api";

async function postJson<T>(path: string, payload: unknown): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload),
  });
  if (!response.ok) {
    const detail = await response.text();
    throw new Error(detail || `请求失败：${response.status}`);
  }
  return response.json() as Promise<T>;
}

export function startKidPlan(childInput: string): Promise<KidPlanStart> {
  return postJson<KidPlanStart>("/kid/plan/start", { child_input: childInput });
}

export function finishKidPlan(sessionId: string, followupAnswers: string): Promise<KidPlanFinish> {
  return postJson<KidPlanFinish>("/kid/plan/finish", { session_id: sessionId, followup_answers: followupAnswers });
}

export function settleKidReflection(reflectionInput: string): Promise<KidSettlement> {
  return postJson<KidSettlement>("/kid/reflection/settle", { reflection_input: reflectionInput });
}
