# S-Shield

AI 기반 학교폭력 상담 및 리포트 자동화 서비스입니다.

사용자가 겪은 상황을 채팅으로 정리하면 AI가 필요한 확인 질문을 이어가고, 상담 내용을 학교, 보호자, 상담 기관, 수사기관에 설명하기 쉬운 리포트 형태로 정리합니다.

| 구분 | 주소 |
| --- | --- |
| Production Web | https://safeshield-tawny.vercel.app |
| Backend Health | https://safeshield-production.up.railway.app/health |
| 발표용 설명 문서 | [EXHIBITION_DESCRIPTION.md](./EXHIBITION_DESCRIPTION.md) |

## 주요 기능

- 로그인/게스트 상담 진입
- AI 상담 질문 생성과 빠른 답변 버튼
- 사건 유형, 관계, 시점, 증거, 영향, 원하는 도움 중심의 리포트 준비도 판단
- 현재 상담 세션 기준 리포트 생성과 조회
- 상담 세션 목록, 메시지 기록, 리포트 기록 관리
- 마이페이지 통계와 기록 삭제
- JWT 인증, Google OAuth 로그인
- 사용자별 세션/리포트 소유권 검증
- DeepSeek 우선, Gemini/Groq fallback 기반 AI 응답
- 국가법령정보센터 Open API 기반 법령 참고 데이터 연결

## 서비스 흐름

1. 사용자가 사건을 자연어로 입력합니다.
2. AI가 상황을 분석하고 추가 확인 질문을 제시합니다.
3. 사용자는 빠른 답변 또는 직접 입력으로 내용을 보완합니다.
4. 필수 정보가 모이면 리포트 생성 CTA가 표시됩니다.
5. 리포트 화면에서 현재 세션의 최신 리포트를 생성하거나 조회합니다.
6. 마이페이지에서 상담 세션과 리포트 기록을 다시 확인합니다.

S-Shield는 법률 자문, 수사, 의료 진단을 대신하지 않습니다. 긴급한 위험이 있으면 보호자, 학교, 경찰(112), 학교폭력 신고/상담(117) 등 실제 기관에 즉시 도움을 요청해야 합니다.

## 기술 스택

| 영역 | 기술 |
| --- | --- |
| Frontend | React 19, Vite, React Router |
| Backend | Java 17, Spring Boot 3.3, Spring Web, Spring Security, Spring Data JPA |
| Auth | JWT, Google OAuth 2.0 |
| Database | H2 local default, PostgreSQL/SQLite runtime driver 포함 |
| AI Providers | DeepSeek, Gemini, Groq |
| External API | 국가법령정보센터 Open API |
| Deployment | Vercel frontend, Railway/Render backend deployment |

## 구성 메모

`backend/main.py`는 이전 실험용 FastAPI 구현입니다. 실제 프론트엔드는 Spring Boot 백엔드(`http://localhost:8080`)를 사용합니다.

## 로컬 실행

### Frontend

```powershell
npm install
npm run dev
```

기본 주소는 `http://localhost:5173`입니다.

### Backend

```powershell
cd backend
.\mvnw.cmd spring-boot:run
```

기본 API 주소는 `http://localhost:8080`입니다.

프론트엔드가 다른 백엔드를 바라보게 하려면 루트 `.env`에 값을 설정합니다.

```env
VITE_API_BASE_URL=http://localhost:8080
```

## 환경 변수

백엔드는 `backend/.env` 또는 배포 플랫폼 secret으로 관리합니다.

```env
JWT_SECRET=32자 이상 권장
FRONTEND_URL=http://localhost:5173
CORS_ALLOWED_ORIGINS=http://localhost:5173,http://127.0.0.1:5173

SPRING_DATASOURCE_URL=jdbc:h2:file:./safeshield-db;AUTO_SERVER=TRUE
SPRING_DATASOURCE_USERNAME=sa
SPRING_DATASOURCE_PASSWORD=

DEEPSEEK_API_KEY=
DEEPSEEK_MODEL=deepseek-v4-flash
DEEPSEEK_MAX_TOKENS=1300

GEMINI_API_KEY=
GROQ_API_KEY=
GROQ_MODEL=llama-3.1-8b-instant
GROQ_MAX_COMPLETION_TOKENS=900
BACKUP_AI_KEY=

LAW_API_OC=

GOOGLE_CLIENT_ID=
GOOGLE_CLIENT_SECRET=
GOOGLE_REDIRECT_URI=http://localhost:8080/login/oauth2/code/google
```

운영 환경에서는 `FRONTEND_URL`, `CORS_ALLOWED_ORIGINS`, `GOOGLE_REDIRECT_URI`를 실제 배포 주소로 바꿔야 합니다.

## 주요 API

| Method | Endpoint | 설명 |
| --- | --- | --- |
| `POST` | `/auth/signup` | 일반 회원가입 |
| `POST` | `/auth/login` | 일반 로그인 |
| `GET` | `/auth/me` | 현재 사용자 확인 |
| `POST` | `/chat/sessions` | 상담 세션 생성 |
| `GET` | `/chat/sessions` | 상담 세션 목록 |
| `GET` | `/chat/sessions/{id}` | 상담 세션 상세 |
| `GET` | `/chat/sessions/{id}/messages` | 세션 메시지 목록 |
| `GET` | `/chat/sessions/{id}/readiness` | 리포트 준비도 |
| `POST` | `/chat/message` | 로그인 사용자 메시지 전송 |
| `POST` | `/chat/guest-message` | 게스트 메시지 전송 |
| `POST` | `/reports/generate` | 리포트 생성 또는 갱신 |
| `GET` | `/reports/session/{sessionId}/latest` | 세션 최신 리포트 |
| `GET` | `/reports/latest` | 사용자 최신 리포트 |
| `GET` | `/users/stats` | 마이페이지 통계 |
| `GET` | `/health` | 백엔드 상태 확인 |

## 프로젝트 구조

```text
SafeShield/
├─ src/
│  ├─ SShieldChat.jsx        # 상담 화면
│  ├─ SShieldResult.jsx      # 리포트 화면
│  ├─ SShieldMypage.jsx      # 마이페이지
│  ├─ SShieldLogin.jsx       # 로그인/게스트 진입
│  ├─ SessionHistory.jsx     # 상담 세션 목록
│  └─ api.js                 # API 클라이언트
├─ backend/
│  ├─ src/main/java/com/safeshield/controller/
│  ├─ src/main/java/com/safeshield/service/
│  ├─ src/main/java/com/safeshield/model/
│  ├─ src/main/java/com/safeshield/repository/
│  └─ src/main/resources/application.properties
├─ Dockerfile.railway-backend
├─ railway.json
├─ vercel.json
└─ README.md
```

## 빌드와 검증

프론트엔드:

```powershell
npm run lint
npm run build
```

백엔드:

```powershell
cd backend
.\mvnw.cmd test
```

## 배포 메모

### Vercel + Railway

- 프론트엔드는 Vercel에 배포합니다.
- 백엔드는 Railway에서 `Dockerfile.railway-backend`를 사용할 수 있습니다.
- `railway.json`은 백엔드 관련 파일 변경 시에만 Railway 재배포가 돌도록 watch pattern을 제한합니다.

### Render Blueprint

`render.yaml`은 Render Blueprint 배포용 설정입니다.

- `safeshield-api`: Spring Boot 백엔드
- `safeshield-web`: Vite 정적 프론트엔드
- `safeshield-db`: PostgreSQL 데이터베이스

Render Dashboard에서 Blueprint를 만들고 이 저장소를 연결한 뒤 AI provider 키, `JWT_SECRET`, Google OAuth 값을 secret으로 입력합니다.

### SQLite 배포

SQLite도 사용할 수 있지만 파일 DB이므로 배포 서버에 영구 볼륨이 필요합니다. 서버 재시작/재배포 후에도 DB 파일이 유지되는 환경에서만 사용하세요.

```env
SPRING_DATASOURCE_URL=jdbc:sqlite:/data/safeshield.db
FRONTEND_URL=https://your-frontend.example
CORS_ALLOWED_ORIGINS=https://your-frontend.example
GOOGLE_REDIRECT_URI=https://your-backend.example/login/oauth2/code/google
```

Fly.io 예시 설정은 `backend/fly.toml.example`에 있습니다.

H2 콘솔은 로컬 디버깅용입니다. 운영 환경에서는 콘솔 노출과 frame option 완화 설정을 닫는 구성이 필요합니다.
