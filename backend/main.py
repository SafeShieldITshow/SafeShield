from fastapi import FastAPI, Depends, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.security import OAuth2PasswordBearer
from pydantic import BaseModel
from typing import Optional
import sqlite3, hashlib, json, os, urllib.request, urllib.error
from datetime import datetime, timedelta, timezone
from pathlib import Path
import jwt

# .env 로드
def _load_env():
    p = Path(__file__).parent / ".env"
    if not p.exists():
        return
    for line in p.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            os.environ.setdefault(k.strip(), v.strip())
_load_env()

try:
    import anthropic
    _anthropic_ok = True
except ImportError:
    _anthropic_ok = False

app = FastAPI(title="SafeShield API")
app.add_middleware(CORSMiddleware, allow_origins=["http://localhost:5173"],
                   allow_credentials=True, allow_methods=["*"], allow_headers=["*"])

SECRET = os.getenv("SECRET_KEY", "safeshield-dev-secret")
ALGORITHM = "HS256"
ANTHROPIC_KEY = os.getenv("ANTHROPIC_API_KEY", "")
GROQ_KEY = os.getenv("GROQ_API_KEY", "")
GROQ_MODEL = os.getenv("GROQ_MODEL", "llama-3.1-8b-instant")
GROQ_MAX_TOKENS = int(os.getenv("GROQ_MAX_COMPLETION_TOKENS", "900"))
GEMINI_KEY = os.getenv("GEMINI_API_KEY", "")
DB = os.path.join(os.path.dirname(__file__), "safeshield.db")

# ── DB ──────────────────────────────────────────────────────────────────────

def get_db():
    conn = sqlite3.connect(DB)
    conn.row_factory = sqlite3.Row
    try:
        yield conn
    finally:
        conn.close()

def init_db():
    conn = sqlite3.connect(DB)
    conn.executescript("""
        CREATE TABLE IF NOT EXISTS users (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            username TEXT UNIQUE NOT NULL,
            pw_hash TEXT NOT NULL,
            name TEXT DEFAULT '',
            email TEXT DEFAULT '',
            created_at TEXT DEFAULT (datetime('now'))
        );
        CREATE TABLE IF NOT EXISTS sessions (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id INTEGER NOT NULL,
            created_at TEXT DEFAULT (datetime('now'))
        );
        CREATE TABLE IF NOT EXISTS messages (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            session_id INTEGER NOT NULL,
            role TEXT NOT NULL,
            content TEXT NOT NULL,
            created_at TEXT DEFAULT (datetime('now'))
        );
        CREATE TABLE IF NOT EXISTS reports (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id INTEGER NOT NULL,
            session_id INTEGER,
            title TEXT NOT NULL,
            summary TEXT DEFAULT '',
            risk_score REAL DEFAULT 0,
            violence_types TEXT DEFAULT '[]',
            matched_laws TEXT DEFAULT '[]',
            law_relevance_scores TEXT DEFAULT '[]',
            expected_measure_range TEXT DEFAULT '[0,5]',
            evidence_guide TEXT DEFAULT '[]',
            created_at TEXT DEFAULT (datetime('now'))
        );
    """)
    # 기존 DB에 컬럼 추가 (마이그레이션)
    for col, default in [
        ("law_relevance_scores", "'[]'"),
        ("expected_measure_range", "'[0,5]'"),
        ("evidence_guide", "'[]'"),
    ]:
        try:
            conn.execute(f"ALTER TABLE reports ADD COLUMN {col} TEXT DEFAULT {default}")
            conn.commit()
        except Exception:
            pass
    conn.commit()
    conn.close()

# ── Auth ─────────────────────────────────────────────────────────────────────

def pw_hash(p): return hashlib.sha256(p.encode()).hexdigest()

def make_token(uid, username):
    return jwt.encode(
        {"sub": str(uid), "username": username,
         "exp": datetime.now(timezone.utc) + timedelta(hours=24)},
        SECRET, algorithm=ALGORITHM)

oauth2 = OAuth2PasswordBearer(tokenUrl="/auth/login")

def current_user(token: str = Depends(oauth2), db=Depends(get_db)):
    try:
        p = jwt.decode(token, SECRET, algorithms=[ALGORITHM])
        uid = int(p["sub"])
    except Exception:
        raise HTTPException(401, "Invalid token")
    u = db.execute("SELECT * FROM users WHERE id=?", (uid,)).fetchone()
    if not u: raise HTTPException(401, "User not found")
    return u

# ── AI ───────────────────────────────────────────────────────────────────────

SYSTEM = """CRITICAL: 반드시 한국어로만 답변하세요.

당신은 S-Shield 법률 AI 상담사입니다. 학교폭력 피해 학생이 상황을 파악하고 법적 대응을 준비할 수 있도록 돕습니다.
아래 법령 원문을 반드시 참고하여 정확한 조항 번호와 처벌 수위를 안내하세요.

[법령 원문]
▶ 학교폭력예방 및 대책에 관한 법률
제2조(정의): "학교폭력"이란 학교 내외에서 학생을 대상으로 발생한 상해, 폭행, 감금, 협박, 명예훼손·모욕, 공갈, 강요, 성폭력, 따돌림, 사이버폭력 등에 의하여 신체·정신 또는 재산의 피해를 수반하는 행위를 말한다.
제16조(피해학생 보호): 심리상담, 일시보호, 치료, 학급교체, 전학 권고 등 조치.
제17조(가해학생 조치): 1호 서면사과 / 2호 접촉금지 / 3호 학교봉사 / 4호 사회봉사 / 5호 특별교육 / 6호 출석정지 / 7호 학급교체 / 8호 전학 / 9호 퇴학. 심각·지속·집단 폭력은 8~9호 가능.

▶ 소년법
제4조: 만 10세 이상 14세 미만 → 형사처벌 불가, 보호처분(소년부 송치). 만 14세 이상 → 형사처벌 가능.
제32조(보호처분): 1호 보호자감호 / 2호 수강명령 / 3호 사회봉사 / 4~5호 보호관찰 / 8호 1개월 소년원 / 9호 단기소년원 / 10호 장기소년원.

▶ 형법
제257조(상해): 7년 이하 징역 또는 1천만원 이하 벌금.
제260조(폭행): 2년 이하 징역 또는 500만원 이하 벌금. 반복·지속 시 가중처벌.
제283조(협박): 3년 이하 징역 또는 500만원 이하 벌금.
제307조(명예훼손): 공연히 사실·허위사실 적시 시 처벌.
제350조(공갈): 10년 이하 징역 또는 2천만원 이하 벌금.

▶ 정보통신망법 제44조의7: 사이버폭력(욕설·비하·허위사실 유포·스토킹성 메시지) 형사처벌 및 학교폭력예방법 조치 대상.
▶ 스토킹처벌법 제18조: 지속·반복 스토킹 3년 이하 징역 또는 3천만원 이하 벌금.
▶ 성폭력처벌법 제13조: 통신매체 이용 성적 수치심 유발 2년 이하 징역 또는 2천만원 이하 벌금.

[대화 방침]
1. 공감하며 신뢰를 형성하세요.
2. 폭력 유형(신체/언어/사이버/따돌림/성폭력/스토킹/갈취)을 파악하세요.
3. 위 법령 원문을 근거로 조항 번호와 처벌 수위를 인용하여 안내하세요.
4. 가해자 나이에 따라 소년법 기준으로 안내하세요.
5. 지금 수집해야 할 증거를 알려주세요 (사진, 녹음, 메시지 캡처, 진단서, 목격자).
6. 증거수집 → 117신고 → 학교폭력신고 → 법적절차 순서로 안내하세요.
7. 3회 이상 대화 후 "충분한 정보가 모였습니다. 분석 리포트를 생성할 수 있습니다."라고 안내하세요.
8. 답변은 3~5문장으로 간결하게. 반드시 법 조항 번호를 인용하세요."""


def _http_post(url, headers, data):
    body = json.dumps(data).encode("utf-8")
    req = urllib.request.Request(url, data=body, headers=headers, method="POST")
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.loads(r.read().decode("utf-8"))


def _call_groq(msgs):
    data = _http_post(
        "https://api.groq.com/openai/v1/chat/completions",
        {"Content-Type": "application/json", "Authorization": f"Bearer {GROQ_KEY}"},
        {"model": GROQ_MODEL, "messages":
            [{"role": "system", "content": SYSTEM}] + msgs,
         "max_tokens": max(400, min(GROQ_MAX_TOKENS, 1200)), "temperature": 0.3}
    )
    return data["choices"][0]["message"]["content"]


def _call_gemini(msgs):
    contents = [{"role": "user" if m["role"] == "user" else "model",
                 "parts": [{"text": m["content"]}]} for m in msgs]
    for model in ["gemini-2.0-flash", "gemini-2.0-flash-lite"]:
        try:
            data = _http_post(
                f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={GEMINI_KEY}",
                {"Content-Type": "application/json"},
                {"systemInstruction": {"parts": [{"text": SYSTEM}]},
                 "contents": contents, "generationConfig": {"maxOutputTokens": 1024}}
            )
            return data["candidates"][0]["content"]["parts"][0]["text"]
        except Exception:
            continue
    raise RuntimeError("Gemini 실패")


def ai_response(msgs):
    if GROQ_KEY:
        try:
            result = _call_groq(msgs)
            print("[AI] Groq OK")
            return result
        except Exception as e:
            print(f"[Groq ERROR] {e}")
    if GEMINI_KEY:
        try:
            result = _call_gemini(msgs)
            print("[AI] Gemini OK")
            return result
        except Exception as e:
            print(f"[Gemini ERROR] {e}")
    if ANTHROPIC_KEY and _anthropic_ok:
        try:
            client = anthropic.Anthropic(api_key=ANTHROPIC_KEY)
            r = client.messages.create(model="claude-sonnet-4-6", max_tokens=1024,
                                       system=SYSTEM, messages=msgs)
            print("[AI] Claude OK")
            return r.content[0].text
        except Exception as e:
            print(f"[Claude ERROR] {e}")
    last = msgs[-1]["content"] if msgs else ""
    if any(k in last for k in ["맞", "폭행", "때", "신체", "주먹", "상해"]):
        return "신체 폭력이 의심됩니다. 형법 제260조(폭행죄) 및 제257조(상해죄)가 적용될 수 있어요. 상해 사진과 병원 진단서를 확보하세요. 언제부터 있었나요?"
    if any(k in last for k in ["욕", "협박", "위협", "모욕"]):
        return "언어 폭력에 해당합니다. 학교폭력예방법 제2조 및 형법 제283조(협박)가 적용됩니다. 대화 내용을 캡처해 두세요."
    if any(k in last for k in ["따돌림", "왕따", "무시"]):
        return "따돌림은 학교폭력예방법 제2조에서 명시적으로 금지됩니다. 언제부터 시작됐나요? 일지로 기록해 두세요."
    if any(k in last for k in ["SNS", "카톡", "사이버", "댓글", "단톡"]):
        return "사이버 폭력은 정보통신망법 제44조의7로도 처벌됩니다. 메시지와 게시물을 URL 포함하여 캡처해 두세요."
    return "상황을 알려주셔서 감사합니다. 어떤 종류의 폭력이 있었나요? 신체 폭력, 언어 폭력, 사이버 폭력, 따돌림 등 자세히 말씀해 주세요."

def analyze(msgs):
    text = " ".join(m["content"] for m in msgs if m.get("role") == "user")
    vt = []
    if any(k in text for k in ["맞", "폭행", "때", "신체", "상해", "주먹", "발로", "밀", "꼬집"]):
        vt.append("신체 폭력")
    if any(k in text for k in ["욕", "언어", "협박", "위협", "욕설", "비하", "모욕", "무섭"]):
        vt.append("언어 폭력")
    if any(k in text for k in ["따돌림", "왕따", "무시", "소외", "집단", "끼워주지", "혼자"]):
        vt.append("지속적 괴롭힘")
    if any(k in text for k in ["SNS", "카톡", "카카오", "사이버", "온라인", "인터넷", "댓글", "단톡", "문자"]):
        vt.append("사이버 폭력")
    if not vt:
        vt = ["언어 폭력"]

    risk = round(min(10.0, 3.0 + len(vt) * 1.5 + (2.0 if "신체 폭력" in vt else 0)), 1)

    laws, scores = [], []
    if "신체 폭력" in vt:
        laws += ["형법 제260조 (폭행)", "형법 제257조 (상해)"]
        scores += [0.9, 0.85]
    if "언어 폭력" in vt:
        laws.append("학교폭력예방 및 대책에 관한 법률 제2조")
        scores.append(0.8)
    if "지속적 괴롭힘" in vt:
        laws.append("학교폭력예방 및 대책에 관한 법률 제16조 (피해학생 보호)")
        scores.append(0.75)
    if "사이버 폭력" in vt:
        laws.append("정보통신망 이용촉진 및 정보보호 등에 관한 법률 제44조의7")
        scores.append(0.8)
    if not laws:
        laws = ["학교폭력예방 및 대책에 관한 법률 제2조"]
        scores = [0.7]

    # 예상 조치 범위: 학교폭력예방법 제17조 1~9호 (0-based index)
    if risk >= 7:
        measure_range = [4, 8]   # 5~9호: 출석정지, 학급교체, 전학, 특별교육, 퇴학
    elif risk >= 4:
        measure_range = [2, 5]   # 3~6호: 접촉금지, 학교봉사, 사회봉사, 출석정지
    else:
        measure_range = [0, 3]   # 1~4호: 서면사과, 접촉금지, 학교봉사, 사회봉사

    # 폭력 유형별 권장 증거 목록
    evidence = []
    if "신체 폭력" in vt:
        evidence += ["상해 사진", "병원 진단서", "음성녹음", "목격자 진술"]
    if "언어 폭력" in vt:
        evidence += ["음성녹음", "메시지 캡처", "목격자 진술"]
    if "사이버 폭력" in vt:
        evidence += ["메시지 캡처", "게시물 스크린샷", "접속 기록"]
    if "지속적 괴롭힘" in vt:
        evidence += ["일지 작성", "목격자 진술", "메시지 캡처"]
    # 중복 제거 (순서 유지)
    seen = set()
    evidence = [e for e in evidence if not (e in seen or seen.add(e))]

    return {
        "violence_types": vt,
        "risk_score": risk,
        "matched_laws": laws,
        "law_relevance_scores": scores,
        "expected_measure_range": measure_range,
        "evidence_guide": evidence,
    }

# ── Models ───────────────────────────────────────────────────────────────────

class SignupReq(BaseModel):
    username: str; password: str; name: str = ""; email: str = ""

class LoginReq(BaseModel):
    username: str; password: str

class MsgReq(BaseModel):
    session_id: Optional[int] = None; content: str

class ReportReq(BaseModel):
    session_id: int; title: str = ""

# ── Routes ───────────────────────────────────────────────────────────────────

@app.post("/auth/signup")
def signup(req: SignupReq, db=Depends(get_db)):
    try:
        db.execute("INSERT INTO users (username,pw_hash,name,email) VALUES (?,?,?,?)",
                   (req.username, pw_hash(req.password), req.name, req.email))
        db.commit()
    except Exception:
        raise HTTPException(400, "이미 사용 중인 아이디입니다.")
    u = db.execute("SELECT * FROM users WHERE username=?", (req.username,)).fetchone()
    return {"token": make_token(u["id"], u["username"]), "username": u["username"], "name": u["name"]}

@app.post("/auth/login")
def login(req: LoginReq, db=Depends(get_db)):
    u = db.execute("SELECT * FROM users WHERE username=? AND pw_hash=?",
                   (req.username, pw_hash(req.password))).fetchone()
    if not u: raise HTTPException(401, "아이디 또는 비밀번호가 틀렸습니다.")
    return {"token": make_token(u["id"], u["username"]), "username": u["username"], "name": u["name"]}

@app.get("/auth/me")
def me(u=Depends(current_user)):
    return {"id": u["id"], "username": u["username"], "name": u["name"], "email": u["email"]}

@app.post("/chat/sessions")
def new_session(u=Depends(current_user), db=Depends(get_db)):
    db.execute("INSERT INTO sessions (user_id) VALUES (?)", (u["id"],))
    db.commit()
    sid = db.execute("SELECT last_insert_rowid()").fetchone()[0]
    return {"session_id": sid}

@app.get("/chat/sessions/{sid}/messages")
def get_msgs(sid: int, u=Depends(current_user), db=Depends(get_db)):
    rows = db.execute("SELECT * FROM messages WHERE session_id=? ORDER BY created_at", (sid,)).fetchall()
    return [dict(r) for r in rows]

@app.post("/chat/message")
def send_msg(req: MsgReq, u=Depends(current_user), db=Depends(get_db)):
    sid = req.session_id
    if not sid:
        db.execute("INSERT INTO sessions (user_id) VALUES (?)", (u["id"],))
        db.commit()
        sid = db.execute("SELECT last_insert_rowid()").fetchone()[0]
    db.execute("INSERT INTO messages (session_id,role,content) VALUES (?,'user',?)", (sid, req.content))
    db.commit()
    history = db.execute("SELECT role,content FROM messages WHERE session_id=? ORDER BY created_at", (sid,)).fetchall()
    ai_msgs = [{"role": r["role"], "content": r["content"]} for r in history]
    reply = ai_response(ai_msgs)
    db.execute("INSERT INTO messages (session_id,role,content) VALUES (?,'assistant',?)", (sid, reply))
    db.commit()
    msg_count = db.execute("SELECT COUNT(*) FROM messages WHERE session_id=? AND role='user'", (sid,)).fetchone()[0]
    return {"session_id": sid, "reply": reply, "user_message_count": msg_count}

@app.post("/reports/generate")
def gen_report(req: ReportReq, u=Depends(current_user), db=Depends(get_db)):
    msgs = db.execute("SELECT role,content FROM messages WHERE session_id=? ORDER BY created_at", (req.session_id,)).fetchall()
    if not msgs: raise HTTPException(400, "대화 내용이 없습니다.")
    a = analyze([{"role": m["role"], "content": m["content"]} for m in msgs])
    title = req.title or f"학교 폭력 상담 ({', '.join(a['violence_types'][:2])})"
    vt_str = '、'.join(a['violence_types'])
    risk_label = '높음' if a['risk_score'] >= 7 else ('중간' if a['risk_score'] >= 4 else '낮음')
    summary = (f"{vt_str} 유형의 폭력이 확인됩니다. "
               f"위험도 {a['risk_score']}/10 ({risk_label}). "
               f"관련 법령: {', '.join(a['matched_laws'][:2])}.")
    db.execute(
        "INSERT INTO reports (user_id,session_id,title,summary,risk_score,"
        "violence_types,matched_laws,law_relevance_scores,expected_measure_range,evidence_guide) "
        "VALUES (?,?,?,?,?,?,?,?,?,?)",
        (u["id"], req.session_id, title, summary, a["risk_score"],
         json.dumps(a["violence_types"], ensure_ascii=False),
         json.dumps(a["matched_laws"], ensure_ascii=False),
         json.dumps(a["law_relevance_scores"], ensure_ascii=False),
         json.dumps(a["expected_measure_range"], ensure_ascii=False),
         json.dumps(a["evidence_guide"], ensure_ascii=False)))
    db.commit()
    rid = db.execute("SELECT last_insert_rowid()").fetchone()[0]
    return {"report_id": rid, "title": title, "summary": summary, **a}

def _parse_report(r):
    d = dict(r)
    for col in ("violence_types", "matched_laws", "law_relevance_scores", "expected_measure_range", "evidence_guide"):
        if col in d and d[col]:
            try:
                d[col] = json.loads(d[col])
            except Exception:
                d[col] = []
        else:
            d[col] = [] if col != "expected_measure_range" else [0, 5]
    return d

@app.get("/reports/latest")
def latest_report(u=Depends(current_user), db=Depends(get_db)):
    r = db.execute("SELECT * FROM reports WHERE user_id=? ORDER BY created_at DESC LIMIT 1", (u["id"],)).fetchone()
    if not r: raise HTTPException(404, "리포트가 없습니다.")
    return _parse_report(r)

@app.get("/reports")
def list_reports(u=Depends(current_user), db=Depends(get_db)):
    rows = db.execute("SELECT * FROM reports WHERE user_id=? ORDER BY created_at DESC", (u["id"],)).fetchall()
    return [_parse_report(r) for r in rows]

@app.get("/reports/{rid}")
def get_report(rid: int, u=Depends(current_user), db=Depends(get_db)):
    r = db.execute("SELECT * FROM reports WHERE id=? AND user_id=?", (rid, u["id"])).fetchone()
    if not r: raise HTTPException(404, "리포트를 찾을 수 없습니다.")
    return _parse_report(r)

@app.delete("/reports/{rid}")
def del_report(rid: int, u=Depends(current_user), db=Depends(get_db)):
    db.execute("DELETE FROM reports WHERE id=? AND user_id=?", (rid, u["id"]))
    db.commit()
    return {"ok": True}

@app.get("/users/stats")
def stats(u=Depends(current_user), db=Depends(get_db)):
    sc = db.execute("SELECT COUNT(*) FROM sessions WHERE user_id=?", (u["id"],)).fetchone()[0]
    rc = db.execute("SELECT COUNT(*) FROM reports WHERE user_id=?", (u["id"],)).fetchone()[0]
    return {"sessions_count": sc, "reports_count": rc,
            "name": u["name"], "email": u["email"], "username": u["username"]}

@app.on_event("startup")
def startup(): init_db()
