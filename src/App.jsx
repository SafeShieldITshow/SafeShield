import { Routes, Route, useNavigate } from 'react-router-dom'
import './App.css'
import SShieldLogin from './SShieldLogin'
import SShieldChat from "./SShieldChat.jsx";
import SShieldResult from "./SShieldResult.jsx";
import SShieldMypage from "./SShieldMypage.jsx";

function MainHome() {
    const navigate = useNavigate();

    return (
        <div className="root-container">
            <div className="gradient-overlay"></div>
            <div className="logo-text">S-<span className="logo-accent">Shield</span></div>

            <div className="main-content">
                <h1 className="title-h1">
                    <div className="line-wrapper">
                        <span className="typing-txt anim-1 text-white">당신의 상황,</span>
                    </div>

                    <div className="line-wrapper line-flex">
                        <span className="fade-txt anim-2 text-purple">법 기준</span>
                        <span className="typing-txt anim-3 text-white">으로</span>
                    </div>

                    <div className="line-wrapper line-flex">
                        <span className="fade-txt anim-4 text-purple">분석</span>
                        <span className="typing-txt anim-5 text-white">해드립니다.</span>
                    </div>
                </h1>

                <p className="sub-desc anim-6">
                    학교폭력 상황을 입력하면 관련 법령을 <br className="pc-br" />
                    바탕으로 대응 방법을 안내합니다.
                </p>

                <div className="anim-7">
                    <button className="start-btn" onClick={() => navigate('/login')}>
                        시작하기
                    </button>
                    <p className="info-note">* 상담 기록 보관을 위해 로그인이 필요합니다.</p>
                </div>
            </div>

            <div className="chat-wrapper">
                <div className="bubble b-white-1"></div>
                <div className="bubble b-purple-1"></div>
                <div className="bubble b-white-2"></div>
                <div className="bubble b-purple-2"></div>
            </div>
        </div>
    );
}

function App() {
    return (
        <Routes>
            <Route path="/" element={<MainHome />} />
            <Route path="/login" element={<SShieldLogin />} />
            <Route path="/chat" element={<SShieldChat />} />
            <Route path="/result" element={<SShieldResult />} />
            <Route path="/mypage" element={<SShieldMypage />} />
        </Routes>
    )
}

export default App