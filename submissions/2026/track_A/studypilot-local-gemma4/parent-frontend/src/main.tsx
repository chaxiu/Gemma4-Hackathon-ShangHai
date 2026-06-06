import React, { useEffect, useMemo, useState } from "react";
import { createRoot } from "react-dom/client";
import { AlertCircle, CheckCircle2, ClipboardCheck, Database, LineChart, RefreshCcw, ShieldCheck, SlidersHorizontal, Sparkles, XCircle } from "lucide-react";
import { correctTask, DayDetail, fetchDay, fetchHistory, fetchOverview, fetchPendingTasks, fetchProfile, HistoryResponse, ParentOverview, PendingTask, ProfileResponse, resolvePending, RuleCheck, TaskOutcome } from "./api";
import "./styles.css";

function isoToday() {
  return new Date().toISOString().slice(0, 10);
}

function dateOffset(days: number) {
  const date = new Date();
  date.setDate(date.getDate() + days);
  return date.toISOString().slice(0, 10);
}

function statusText(status: TaskOutcome["status"]) {
  return status === "completed" ? "已完成" : status === "partial" ? "部分完成" : "未完成";
}

function statusIcon(status: TaskOutcome["status"]) {
  if (status === "completed") return <CheckCircle2 />;
  if (status === "partial") return <AlertCircle />;
  return <XCircle />;
}

function App() {
  const today = useMemo(isoToday, []);
  const [overview, setOverview] = useState<ParentOverview | null>(null);
  const [day, setDay] = useState<DayDetail | null>(null);
  const [history, setHistory] = useState<HistoryResponse | null>(null);
  const [pending, setPending] = useState<PendingTask[]>([]);
  const [profile, setProfile] = useState<ProfileResponse | null>(null);
  const [debugOpen, setDebugOpen] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");

  async function loadAll() {
    setLoading(true);
    setError("");
    try {
      const [overviewResult, dayResult, historyResult, pendingResult, profileResult] = await Promise.all([
        fetchOverview(today),
        fetchDay(today),
        fetchHistory(dateOffset(-13), today),
        fetchPendingTasks(),
        fetchProfile(),
      ]);
      setOverview(overviewResult);
      setDay(dayResult);
      setHistory(historyResult);
      setPending(pendingResult.tasks);
      setProfile(profileResult);
    } catch (err) {
      setError(err instanceof Error ? err.message : "家长端数据加载失败");
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void loadAll();
  }, []);

  async function markTask(outcome: TaskOutcome, nextStatus: TaskOutcome["status"]) {
    if (!day) return;
    const result = await correctTask(day.date, outcome.title, outcome.status, nextStatus, "家长审核纠偏");
    const [freshOverview, freshHistory, freshPending] = await Promise.all([
      fetchOverview(today),
      fetchHistory(dateOffset(-13), today),
      fetchPendingTasks(),
    ]);
    setDay(result.day);
    setOverview(freshOverview);
    setHistory(freshHistory);
    setPending(freshPending.tasks);
  }

  async function closePending(task: PendingTask) {
    await resolvePending(task.pending_id);
    const [freshOverview, freshDay, freshPending] = await Promise.all([
      fetchOverview(today),
      fetchDay(today),
      fetchPendingTasks(),
    ]);
    setOverview(freshOverview);
    setDay(freshDay);
    setPending(freshPending.tasks);
  }

  const completion = Math.round(((day?.daily_log?.completion_rate ?? overview?.recent.average_completion_rate ?? 0) || 0) * 100);

  return (
    <main className="parent-shell">
      <header className="parent-header">
        <div>
          <span className="eyebrow">StudyPilot Parent</span>
          <h1>家长观察台</h1>
          <p>看真实完成，轻量纠偏，把明天接住。</p>
        </div>
        <div className="header-actions">
          <span className="official-badge">正式数据</span>
          <button className="ghost-button" onClick={() => void loadAll()} disabled={loading}>
            <RefreshCcw size={18} />
            刷新
          </button>
        </div>
      </header>

      {error && <div className="error">{error}</div>}

      <section className="summary-grid">
        <MetricCard label="今日完成率" value={`${completion}%`} hint={day?.daily_log ? "来自孩子睡前复盘" : "等待今日结算"} icon={<ClipboardCheck />} />
        <MetricCard label="近两周均值" value={`${Math.round((overview?.recent.average_completion_rate ?? 0) * 100)}%`} hint={`${overview?.recent.days_with_logs ?? 0} 天有记录`} icon={<LineChart />} />
        <MetricCard label="待轻量接住" value={`${overview?.pending_count ?? 0}`} hint="未完成不硬补，进入明天小步" icon={<ShieldCheck />} />
      </section>

      <section className="content-grid">
        <Panel title="今日总览" icon={<Sparkles />}>
          <div className="today-card">
            <span>{today}</span>
            <h2>{day?.plan?.plan_title ?? "今天还没有计划记录"}</h2>
            <p>{day?.daily_log?.encouragement ?? "孩子完成睡前结算后，这里会显示鼓励和真实结果。"}</p>
          </div>
        </Panel>

        <Panel title="判断依据" icon={<ShieldCheck />}>
          <DecisionEvidence day={day} />
        </Panel>

        <Panel title="真实完成" icon={<ClipboardCheck />}>
          <div className="task-list">
            {(day?.task_outcomes ?? []).length === 0 && <p className="muted">还没有真实完成记录。</p>}
            {(day?.task_outcomes ?? []).map((task) => (
              <article className={`task-row ${task.status}`} key={task.outcome_id}>
                <div className="status-icon">{statusIcon(task.status)}</div>
                <div>
                  <h3>{task.title}</h3>
                  <p>{statusText(task.status)} · {task.evidence || task.reason || task.remaining || "等待更多证据"}</p>
                </div>
                <div className="task-actions">
                  <button onClick={() => void markTask(task, "completed")}>已完成</button>
                  <button onClick={() => void markTask(task, "partial")}>部分</button>
                  <button onClick={() => void markTask(task, "missed")}>未完成</button>
                </div>
              </article>
            ))}
          </div>
        </Panel>

        <Panel title="趋势" icon={<LineChart />}>
          <div className="bars">
            {(history?.days ?? []).map((item) => (
              <div className="bar-row" key={item.date}>
                <span>{item.date.slice(5)}</span>
                <div><i style={{ width: `${Math.round(item.completion_rate * 100)}%` }} /></div>
                <strong>{Math.round(item.completion_rate * 100)}%</strong>
              </div>
            ))}
            {(history?.days ?? []).length === 0 && <p className="muted">暂无历史趋势。</p>}
          </div>
        </Panel>

        <Panel title="待跟进" icon={<ShieldCheck />}>
          <div className="pending-list">
            {pending.length === 0 && <p className="muted">当前没有需要明天接住的任务。</p>}
            {pending.map((task) => (
              <article className="pending-card" key={task.pending_id}>
                <span>{task.source_date}</span>
                <h3>{task.title}</h3>
                <p>{task.suggested_next_step || task.reason || "明天用一个小步继续。"}</p>
                <button onClick={() => void closePending(task)}>已接住</button>
              </article>
            ))}
          </div>
        </Panel>

        <Panel title="学习档案" icon={<SlidersHorizontal />}>
          <ProfileSummary profile={profile} />
        </Panel>

        <Panel title="调试抽屉" icon={<Database />}>
          <button className="secondary-button" onClick={() => setDebugOpen((open) => !open)}>
            {debugOpen ? "收起调试信息" : "查看 JSON / RAG"}
          </button>
          {debugOpen && (
            <pre className="debug-box">{JSON.stringify({ run_mode: day?.run_mode ?? "official", session_id: day?.session_id, business_date: day?.business_date, day, overview, profile }, null, 2)}</pre>
          )}
        </Panel>
      </section>
    </main>
  );
}

function MetricCard({ label, value, hint, icon }: { label: string; value: string; hint: string; icon: React.ReactNode }) {
  return (
    <article className="metric-card">
      <div className="metric-icon">{icon}</div>
      <span>{label}</span>
      <strong>{value}</strong>
      <p>{hint}</p>
    </article>
  );
}

function Panel({ title, icon, children }: { title: string; icon: React.ReactNode; children: React.ReactNode }) {
  return (
    <section className="panel">
      <div className="panel-title">
        <span>{icon}</span>
        <h2>{title}</h2>
      </div>
      {children}
    </section>
  );
}

function ProfileSummary({ profile }: { profile: ProfileResponse | null }) {
  const data = profile?.profile ?? {};
  const student = (data.student ?? {}) as Record<string, unknown>;
  const subjects = (data.subjects ?? {}) as Record<string, Record<string, unknown>>;
  return (
    <div className="profile-summary">
      <h3>{String(student.nickname ?? "孩子")} · {String(student.grade ?? "学习档案")}</h3>
      <p>{String((data.family_goal as Record<string, unknown> | undefined)?.primary_goal ?? "家长确认后，这里会显示家庭目标。")}</p>
      <div className="subject-grid">
        {Object.entries(subjects).slice(0, 3).map(([key, subject]) => (
          <span key={key}>{key}: {String(subject.status ?? "待观察")}</span>
        ))}
      </div>
    </div>
  );
}

function DecisionEvidence({ day }: { day: DayDetail | null }) {
  const raw = day?.plan?.raw ?? {};
  const checks = (raw.rule_checks ?? []) as RuleCheck[];
  const repairedChecks = checks.filter((check) => check.issue_code && check.passed === false).slice(0, 4);
  const chunkIds = (raw.rag_chunk_ids ?? raw.available_rag_chunk_ids ?? []).filter(Boolean).slice(0, 5);
  const explanation = raw.parent_explanation || raw.why_this_is_enough || raw.burden_reduction_note;

  if (!day?.plan) {
    return <p className="muted">生成今日计划后，这里会解释模型安排依据。</p>;
  }

  return (
    <div className="evidence-stack">
      <p>{explanation || "本次计划主要依据孩子输入、减负规则和学习档案片段生成。"}</p>
      <div className="evidence-list">
        {repairedChecks.length === 0 && <span>规则检查：未发现需要家长处理的风险</span>}
        {repairedChecks.map((check) => (
          <span key={`${check.rule_id}-${check.issue_code}`}>{check.title || check.rule_id}: {check.detail || "已按规则修正"}</span>
        ))}
      </div>
      {chunkIds.length > 0 && (
        <div className="chunk-list">
          {chunkIds.map((chunkId) => <code key={chunkId}>{chunkId}</code>)}
        </div>
      )}
    </div>
  );
}

createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);
