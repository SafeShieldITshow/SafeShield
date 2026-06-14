import { Navigate, Route, Routes, useNavigate, useSearchParams } from 'react-router-dom';
import { useEffect, useState } from 'react';
import './App.css';
import SShieldLogin from './SShieldLogin';
import SShieldChat from './SShieldChat.jsx';
import SShieldResult from './SShieldResult.jsx';
import SShieldMypage from './SShieldMypage.jsx';
import { api, clearSession, hasToken, setToken } from './api.js';

function LoginRequiredRedirect() {
    const navigate = useNavigate();

    useEffect(() => {
        alert('로그인이 필요합니다.');
        navigate('/', { replace: true });
    }, [navigate]);

    return <div style={{ color: '#fff', textAlign: 'center', marginTop: 80 }}>메인으로 이동 중...</div>;
}

function ProtectedRoute({ children }) {
    const [status, setStatus] = useState(() => hasToken() ? 'checking' : 'guest');

    useEffect(() => {
        let alive = true;

        if (!hasToken()) {
            clearSession();
            setStatus('guest');
            return;
        }

        api.get('/auth/me')
            .then(() => {
                if (alive) setStatus('authed');
            })
            .catch(() => {
                clearSession();
                if (alive) setStatus('guest');
            });

        return () => {
            alive = false;
        };
    }, []);

    if (status === 'checking') {
        return <div style={{ color: '#fff', textAlign: 'center', marginTop: 80 }}>로그인 상태 확인 중...</div>;
    }

    return status === 'authed' ? children : <LoginRequiredRedirect />;
}

function OAuth2Callback() {
    const [params] = useSearchParams();
    const navigate = useNavigate();

    useEffect(() => {
        const token = params.get('token');
        const username = params.get('username');
        const name = params.get('name');

        if (token) {
            setToken(token);
            localStorage.setItem('ss_username', username || '');
            localStorage.setItem('ss_name', name || username || '');
            navigate('/chat', { replace: true });
        } else {
            navigate('/login', { replace: true });
        }
    }, [navigate, params]);

    return <div style={{ color: '#fff', textAlign: 'center', marginTop: 80 }}>로그인 처리 중...</div>;
}

function MainHome() {
    const navigate = useNavigate();

    return (
        <div className="root-container">
            <header className="home-header">
                <button className="logo-text" onClick={() => navigate('/chat?new=1')} aria-label="새 상담 시작">
                    S-<span className="logo-accent">Shield</span>
                </button>
                <button className="home-login" onClick={() => navigate('/login')}>
                    로그인
                </button>
            </header>

            <main className="main-content">
                <p className="home-kicker">학교폭력 법률 상담 AI</p>
                <h1 className="title-h1">학교폭력 상황을 법 기준으로 정리합니다.</h1>

                <p className="sub-desc">
                    상황을 입력하면 유형, 관련 법령, 증거 준비와 다음 조치를 한 화면에 정리합니다.
                </p>

                <div className="home-actions">
                    <button className="start-btn" onClick={() => navigate('/chat?new=1')}>
                        상담 시작
                    </button>
                    <button className="secondary-btn" onClick={() => navigate('/login')}>
                        로그인
                    </button>
                </div>
                <p className="info-note">상담 기록과 리포트 저장은 로그인 후 사용할 수 있습니다.</p>
            </main>
        </div>
    );
}

function App() {
    return (
        <Routes>
            <Route path="/" element={<MainHome />} />
            <Route path="/login" element={<SShieldLogin />} />
            <Route path="/oauth2/callback" element={<OAuth2Callback />} />
            <Route path="/chat" element={<SShieldChat />} />
            <Route path="/result" element={<SShieldResult />} />
            <Route path="/mypage" element={<ProtectedRoute><SShieldMypage /></ProtectedRoute>} />
            <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
    );
}

export default App;
