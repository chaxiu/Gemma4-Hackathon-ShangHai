export type TaskOutcome = {
  outcome_id: string;
  title: string;
  subject: string;
  status: "completed" | "partial" | "missed";
  evidence?: string;
  reason?: string;
  remaining?: string;
  corrected: number;
};

export type PendingTask = {
  pending_id: string;
  title: string;
  subject: string;
  reason?: string;
  suggested_next_step?: string;
  status: "open" | "resolved";
  source_date?: string;
};

export type RuleCheck = {
  rule_id?: string;
  title?: string;
  passed?: boolean;
  severity?: string;
  detail?: string;
  issue_code?: string;
  repair_hint?: string;
  blocking?: boolean;
};

export type DayDetail = {
  date: string;
  business_date?: string;
  run_mode?: "official" | "debug";
  session_id?: string;
  plan: null | {
    plan_title: string;
    total_minutes: number;
    tasks: Array<{ title: string; subject: string; minutes: number }>;
    raw?: {
      parent_explanation?: string;
      why_this_is_enough?: string;
      burden_reduction_note?: string;
      rule_checks?: RuleCheck[];
      rag_chunk_ids?: string[];
      available_rag_chunk_ids?: string[];
    };
  };
  daily_log: null | {
    summary: string;
    completion_rate: number;
    energy_level: string;
    mood_signal?: string;
    encouragement?: string;
    source_input?: string;
    raw?: unknown;
  };
  task_outcomes: TaskOutcome[];
  pending_tasks: PendingTask[];
  corrections: Array<{ correction_id: string; target_id: string; field: string; old_value?: string; new_value: string; reason?: string }>;
  profile_snapshots?: Array<{ snapshot_id: string; path: string; created_at: string; reason?: string }>;
};

export type ParentOverview = {
  today: DayDetail;
  recent: { from: string; to: string; average_completion_rate: number; days_with_logs: number; weak_points: string[] };
  pending_count: number;
  pending_tasks: PendingTask[];
};

export type HistoryResponse = {
  from: string;
  to: string;
  days: Array<{ date: string; completion_rate: number; energy_level: string; summary: string; encouragement?: string }>;
};

export type ProfileResponse = {
  profile: Record<string, unknown>;
  markdown: string;
  chunks: unknown[];
};

const API_BASE = import.meta.env.VITE_STUDYPILOT_API_BASE ?? "/api";

async function requestJson<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, init);
  if (!response.ok) {
    const detail = await response.text();
    throw new Error(detail || `请求失败：${response.status}`);
  }
  return response.json() as Promise<T>;
}

export function fetchOverview(today: string): Promise<ParentOverview> {
  return requestJson<ParentOverview>(`/parent/overview?today=${today}`);
}

export function fetchDay(date: string): Promise<DayDetail> {
  return requestJson<DayDetail>(`/parent/days/${date}`);
}

export function fetchHistory(fromDate: string, toDate: string): Promise<HistoryResponse> {
  return requestJson<HistoryResponse>(`/parent/history?from_date=${fromDate}&to_date=${toDate}`);
}

export function fetchPendingTasks(): Promise<{ tasks: PendingTask[] }> {
  return requestJson<{ tasks: PendingTask[] }>("/parent/pending-tasks");
}

export function fetchProfile(): Promise<ProfileResponse> {
  return requestJson<ProfileResponse>("/profile");
}

export function correctTask(date: string, targetId: string, oldValue: string, newValue: string, reason: string): Promise<{ day: DayDetail }> {
  return requestJson<{ day: DayDetail }>(`/parent/days/${date}/corrections`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      target_type: "task_outcome",
      target_id: targetId,
      field: "status",
      old_value: oldValue,
      new_value: newValue,
      reason,
    }),
  });
}

export function resolvePending(pendingId: string): Promise<{ task: PendingTask }> {
  return requestJson<{ task: PendingTask }>(`/parent/pending-tasks/${encodeURIComponent(pendingId)}/resolve`, { method: "POST" });
}
