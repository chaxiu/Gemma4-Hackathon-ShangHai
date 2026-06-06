import React, { useMemo, useState } from "react";
import { createRoot } from "react-dom/client";
import { CheckCircle2, ChevronRight, Clock3, Compass, MessageCircle, Moon, RotateCcw, Send, Sparkles, Star, Trophy } from "lucide-react";
import { finishKidPlan, KidPlanFinish, KidPlanStart, KidSettlement, settleKidReflection, startKidPlan } from "./api";
import "./styles.css";

type Stage = "start" | "followup" | "plan" | "reflection" | "settlement";

const defaultChildInput = "我今天4点半到家，有语文大小册和习字册，数学还有一点大小册和口头作业，英语有背诵和抄写。还有阅读和打卡题。我今天有点累，不知道先做什么。";
const defaultReflection = "数学大小册和口头作业做完了，英语背诵和抄写也完成了。语文大小册做完了，习字册还剩一点。阅读没做，打卡题也没做，今天有点累。";

function todayLabels() {
  const now = new Date();
  const weekdays = ["星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六"];
  return {
    date: `${now.getFullYear()}年${now.getMonth() + 1}月${now.getDate()}日`,
    weekday: weekdays[now.getDay()],
  };
}

function App() {
  const fallbackDate = useMemo(todayLabels, []);
  const [stage, setStage] = useState<Stage>("start");
  const [childInput, setChildInput] = useState(defaultChildInput);
  const [answer, setAnswer] = useState("");
  const [reflectionInput, setReflectionInput] = useState(defaultReflection);
  const [planStart, setPlanStart] = useState<KidPlanStart | null>(null);
  const [plan, setPlan] = useState<KidPlanFinish | null>(null);
  const [settlement, setSettlement] = useState<KidSettlement | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");

  const dateLabel = planStart?.date_label ?? plan?.date_label ?? settlement?.date_label ?? fallbackDate.date;
  const weekdayLabel = planStart?.weekday_label ?? plan?.weekday_label ?? settlement?.weekday_label ?? fallbackDate.weekday;

  async function run<T>(work: () => Promise<T>, after: (result: T) => void) {
    setLoading(true);
    setError("");
    try {
      const result = await work();
      after(result);
    } catch (err) {
      setError(err instanceof Error ? err.message : "请求失败，请稍后再试。");
    } finally {
      setLoading(false);
    }
  }

  return (
    <main className="app-shell">
      <div className="aurora aurora-one" />
      <div className="aurora aurora-two" />
      <section className="quest-frame">
        <Header dateLabel={dateLabel} weekdayLabel={weekdayLabel} onReset={() => {
          setStage("start");
          setPlanStart(null);
          setPlan(null);
          setSettlement(null);
          setAnswer("");
          setError("");
        }} />
        {error && <div className="error-panel">{error}</div>}
        {stage === "start" && (
          <StartPanel
            childInput={childInput}
            setChildInput={setChildInput}
            loading={loading}
            onStart={() => run(() => startKidPlan(childInput), (result) => {
              setPlanStart(result);
              setStage("followup");
            })}
          />
        )}
        {stage === "followup" && planStart && (
          <FollowupPanel
            planStart={planStart}
            answer={answer}
            setAnswer={setAnswer}
            loading={loading}
            onFinish={() => run(() => finishKidPlan(planStart.session_id, answer), (result) => {
              setPlan(result);
              setStage("plan");
            })}
          />
        )}
        {stage === "plan" && plan && <PlanPanel plan={plan} onReflect={() => setStage("reflection")} />}
        {stage === "reflection" && (
          <ReflectionPanel
            reflectionInput={reflectionInput}
            setReflectionInput={setReflectionInput}
            loading={loading}
            onSettle={() => run(() => settleKidReflection(reflectionInput), (result) => {
              setSettlement(result);
              setStage("settlement");
            })}
          />
        )}
        {stage === "settlement" && settlement && <SettlementPanel settlement={settlement} onRestart={() => setStage("start")} />}
      </section>
    </main>
  );
}

function Header({ dateLabel, weekdayLabel, onReset }: { dateLabel: string; weekdayLabel: string; onReset: () => void }) {
  return (
    <header className="topbar">
      <div>
        <div className="eyebrow">StudyPilot Kid Quest</div>
        <h1>今天的小任务舱</h1>
      </div>
      <div className="date-chip">
        <Clock3 size={18} />
        <span>{dateLabel}</span>
        <strong>{weekdayLabel}</strong>
      </div>
      <button className="icon-button" onClick={onReset} aria-label="重新开始">
        <RotateCcw size={19} />
      </button>
    </header>
  );
}

function StartPanel({ childInput, setChildInput, loading, onStart }: { childInput: string; setChildInput: (value: string) => void; loading: boolean; onStart: () => void }) {
  return (
    <div className="layout-two">
      <CompanionCard title="我先听你说" text="告诉我今天有什么、还剩多少时间、现在累不累。我会先问一个关键问题，再开始安排。" />
      <section className="glass-card main-card">
        <div className="card-title-row">
          <div>
            <span className="pill">第一步</span>
            <h2>今天放学后怎么样？</h2>
          </div>
          <Compass className="title-icon" />
        </div>
        <textarea value={childInput} onChange={(event) => setChildInput(event.target.value)} />
        <button className="primary-button" onClick={onStart} disabled={loading || !childInput.trim()}>
          {loading ? "正在帮你把任务变轻一点..." : "开始今日计划"}
          <Send size={18} />
        </button>
      </section>
    </div>
  );
}

function FollowupPanel({ planStart, answer, setAnswer, loading, onFinish }: { planStart: KidPlanStart; answer: string; setAnswer: (value: string) => void; loading: boolean; onFinish: () => void }) {
  return (
    <div className="layout-two">
      <CompanionCard title="AI 先问一句" text={planStart.companion_message} />
      <section className="glass-card main-card">
        <div className="card-title-row">
          <div>
            <span className="pill">第二步</span>
            <h2>补充一下，我就开始干活</h2>
          </div>
          <MessageCircle className="title-icon" />
        </div>
        <div className="question-stack">
          {planStart.questions.map((question) => (
            <div className="question-card" key={question.id}>
              <strong>{question.question}</strong>
              <span>{question.why}</span>
            </div>
          ))}
        </div>
        <textarea value={answer} onChange={(event) => setAnswer(event.target.value)} placeholder="比如：数学明天交，我现在有点累。" />
        <button className="primary-button" onClick={onFinish} disabled={loading || !answer.trim()}>
          {loading ? "正在检查今天不会太满..." : "生成今日闯关计划"}
          <ChevronRight size={20} />
        </button>
      </section>
    </div>
  );
}

function PlanPanel({ plan, onReflect }: { plan: KidPlanFinish; onReflect: () => void }) {
  return (
    <div className="plan-grid">
      <section className="glass-card map-card">
        <div className="card-title-row">
          <div>
            <span className="pill">今日闯关</span>
            <h2>{plan.plan_title}</h2>
          </div>
          <Trophy className="title-icon" />
        </div>
        <div className="quest-path">
          {plan.tasks.map((task, index) => (
            <article className="quest-node" key={`${task.title}-${index}`}>
              <div className="node-orbit">{index + 1}</div>
              <div>
                <span>{task.quest_label}</span>
                <h3>{task.title}</h3>
                <p>{task.minutes} 分钟 · {task.completion_standard}</p>
                <Stars count={task.reward_stars} />
              </div>
            </article>
          ))}
        </div>
      </section>
      <aside className="glass-card side-card">
        <span className="pill">今天到这里就很好</span>
        <h2>{plan.total_minutes} 分钟</h2>
        <p>{plan.child_message}</p>
        {plan.deferred_tasks.length > 0 && (
          <div className="soft-box">
            <strong>先收好的任务</strong>
            <p>{plan.deferred_tasks.map((task) => task.title).join("、")} 不用今晚硬补。</p>
          </div>
        )}
        <button className="secondary-button" onClick={onReflect}>
          晚上来结算
          <Moon size={18} />
        </button>
      </aside>
    </div>
  );
}

function ReflectionPanel({ reflectionInput, setReflectionInput, loading, onSettle }: { reflectionInput: string; setReflectionInput: (value: string) => void; loading: boolean; onSettle: () => void }) {
  return (
    <div className="layout-two">
      <CompanionCard title="睡前只说一次" text="说说完成了什么、哪里卡住、现在感觉怎样。今晚不加任务，只做今日结算。" />
      <section className="glass-card main-card">
        <div className="card-title-row">
          <div>
            <span className="pill">睡前复盘</span>
            <h2>今天怎么样？</h2>
          </div>
          <Moon className="title-icon" />
        </div>
        <textarea value={reflectionInput} onChange={(event) => setReflectionInput(event.target.value)} />
        <button className="primary-button" onClick={onSettle} disabled={loading || !reflectionInput.trim()}>
          {loading ? "正在结算今天..." : "生成今日结算"}
          <Sparkles size={18} />
        </button>
      </section>
    </div>
  );
}

function SettlementPanel({ settlement, onRestart }: { settlement: KidSettlement; onRestart: () => void }) {
  return (
    <div className="settlement-grid">
      <section className="glass-card settlement-card">
        <span className="pill">今日结算</span>
        <h2>{settlement.encouragement}</h2>
        <div className="big-stars"><Stars count={settlement.effort_stars} /></div>
        <div className="settle-meter">
          <span>完成率</span>
          <strong>{Math.round(settlement.completion_rate * 100)}%</strong>
          <div><i style={{ width: `${Math.round(settlement.completion_rate * 100)}%` }} /></div>
        </div>
      </section>
      <aside className="glass-card side-card">
        <div className="result-row">
          <CheckCircle2 />
          <p>{settlement.completed_summary}</p>
        </div>
        {settlement.stuck_points.length > 0 && (
          <div className="soft-box">
            <strong>今天卡住的地方</strong>
            <p>{settlement.stuck_points.join("、")}</p>
          </div>
        )}
        <div className="soft-box">
          <strong>没完成也收好了</strong>
          <p>{settlement.pending_summary}</p>
        </div>
        <button className="secondary-button" onClick={onRestart}>
          结束今天
          <Sparkles size={18} />
        </button>
      </aside>
    </div>
  );
}

function CompanionCard({ title, text }: { title: string; text: string }) {
  return (
    <aside className="companion-card">
      <div className="bot-face">
        <span />
        <span />
      </div>
      <div>
        <span className="pill dark">学习伙伴在线</span>
        <h2>{title}</h2>
        <p>{text}</p>
      </div>
    </aside>
  );
}

function Stars({ count }: { count: number }) {
  return (
    <div className="stars" aria-label={`${count} 颗努力星`}>
      {Array.from({ length: count }).map((_, index) => <Star key={index} size={18} fill="currentColor" />)}
    </div>
  );
}

createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);
