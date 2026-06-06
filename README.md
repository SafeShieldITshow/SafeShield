# S-Shield

학교폭력 상황을 AI로 분석하고 관련 법령, 증거 준비 방법, 대응 절차를 안내하는 상담 플랫폼입니다.

## 구성

- Frontend: React 19, Vite
- Backend: Spring Boot 3, Spring Security, JPA
- Database: H2 파일 데이터베이스 (`backend/safeshield-db.mv.db`)
- AI: Groq, Gemini, Anthropic 순차 연결
- Law data: 국가법령정보센터 Open API
- Login: 일반 계정 JWT 인증, Google OAuth 2.0

`backend/main.py`는 이전 실험용 구현이며 실제 프런트엔드는 Spring Boot 서버(`localhost:8080`)를 사용합니다.

## 주요 기능

- 일반 회원가입/로그인과 Google OAuth 로그인
- 로그인된 사용자별 상담 세션 저장
- Groq 우선, Gemini/Anthropic 백업 순서의 실제 AI 상담 응답
- 국가법령정보센터 Open API 기반 법령 문맥 연결
- AI 답변의 법령 인용 검증 및 외국어/중복 면책 문구 제거
- 상담 중 리포트 생성 가능 여부 확인
- 부족한 정보가 있으면 리포트 생성 전 확인 질문 안내
- 새 사건으로 보이는 입력은 같은 사건인지 확인 후 세션 분리
- 리포트의 위험도, 예상 조치 범위, 핵심 판단 근거, 권장 조치 표시

## 상담/리포트 흐름

1. 사용자는 회원가입 후 반드시 로그인해야 상담 화면에 진입할 수 있습니다.
2. 상담 내용은 사용자별 세션으로 저장됩니다.
3. AI는 현재 대화의 사건 내용, 학교 관계, 시점/반복성, 증거 유무를 기준으로 답변합니다.
4. 리포트 생성 전 `추가 확인 필요` 여부를 판단합니다.
5. 리포트와 채팅은 같은 준비 상태 판정 기준을 사용합니다.
6. 다른 사건으로 보이는 입력은 사용자가 선택한 경우에만 새 상담으로 분리됩니다.

## 위험도 산정 기준

위험도는 0~10 사이 점수로 계산됩니다.

- 일반 언어 폭력/사이버 비방은 과도하게 높게 잡지 않도록 중간 이하로 제한합니다.
- 반복성, 직접 협박, 신체 상해, 병원 진단, 성폭력, 스토킹, 갈취 단서가 있으면 가중됩니다.
- 학교폭력 해당성이 낮은 경우 위험도 상한을 낮춰 리포트가 과장되지 않게 합니다.
- 예상 조치 범위는 위험도에 따라 누적 구간으로 표시됩니다.

## 환경 변수

`backend/.env`에 다음 값을 설정합니다.

```dotenv
JWT_SECRET=32자 이상의 충분히 긴 비밀키
GROQ_API_KEY=...
GEMINI_API_KEY=...
BACKUP_AI_KEY=...
LAW_API_OC=...
GOOGLE_CLIENT_ID=...
GOOGLE_CLIENT_SECRET=...
```

`BACKUP_AI_KEY`는 선택 사항이며, 나머지 연동을 실제로 사용할 경우 해당 키가 필요합니다.

Google Cloud Console의 승인된 리디렉션 URI에는 다음 주소가 등록되어야 합니다.

```text
http://localhost:8080/login/oauth2/code/google
```

## 실행

백엔드:

```powershell
cd backend
.\mvnw.cmd spring-boot:run
```

프런트엔드:

```powershell
npm install
npm run dev
```

접속 주소는 `http://127.0.0.1:5173`입니다. API 주소를 변경하려면 프런트 실행 전에 `VITE_API_BASE_URL`을 설정합니다.

## 검증

```powershell
npm run lint
npm run build
cd backend
.\mvnw.cmd test
```

## Git

현재 저장소에 원격 저장소가 설정되어 있어야 push가 가능합니다.

```powershell
git remote add origin <repository-url>
git push -u origin master
```
