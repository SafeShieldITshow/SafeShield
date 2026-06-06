import React, { useEffect, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import './SShieldResult.css';
import { api, clearSession } from './api.js';
import SessionHistory from './SessionHistory.jsx';

const EVIDENCE_MAP = {
    '음성 녹음': { icon: '녹음', desc: '위협, 욕설, 협박 발언을 날짜와 시간 정보가 남도록 보관하세요.' },
    '상해 사진': { icon: '사진', desc: '멍, 상처 등 피해 부위를 여러 각도에서 촬영하고 촬영 날짜를 남기세요.' },
    '병원 진단서': { icon: '진단', desc: '의사 진단서, 치료 기록, 약 처방 기록을 함께 보관하세요.' },
    '메시지 캡처': { icon: '캡처', desc: '카카오톡, 문자, DM, SNS 메시지는 보낸 사람과 시간이 보이게 캡처하세요.' },
    '게시물 스크린샷': { icon: '게시물', desc: '게시물 URL, 작성자, 댓글, 업로드 시간이 보이도록 저장하세요.' },
    '목격자 진술': { icon: '진술', desc: '목격한 친구, 선생님, 주변인의 이름과 연락 가능 정보를 정리하세요.' },
    '일지 작성': { icon: '기록', desc: '날짜, 장소, 가해자, 목격자, 발생 내용을 빠짐없이 적어두세요.' },
    '접속 기록': { icon: '기록', desc: '온라인 괴롭힘은 계정명, 링크, 접속 기록, 알림 내역을 보관하세요.' },
    '게시물 전체 화면 캡처': { icon: '게시물', desc: '본문, 사진, 댓글, 작성자, 게시 시간이 한 화면에 보이게 저장하세요.' },
    'URL·작성자·게시시간 기록': { icon: '링크', desc: '주소, 게시글 번호, 계정 아이디, 닉네임, 업로드 시간을 따로 적어두세요.' },
    '원본 이미지·댓글 백업': { icon: '원본', desc: '사진 원본, 댓글 흐름, 알림 화면을 삭제 전 별도 폴더에 보관하세요.' },
    '계정 프로필 캡처': { icon: '계정', desc: '상대 계정의 아이디, 닉네임, 프로필 사진, 소개글이 보이게 캡처하세요.' },
    '대화방 전체 캡처': { icon: '대화', desc: '앞뒤 맥락, 참여자 목록, 보낸 시간까지 보이도록 길게 저장하세요.' },
    '상처 사진 촬영': { icon: '사진', desc: '멍이나 상처를 가까이와 전체 위치 두 방식으로 찍고 촬영 날짜를 남기세요.' },
    '진단서·진료 기록': { icon: '진단', desc: '진단서, 진료비 영수증, 처방전, 치료 날짜를 함께 보관하세요.' },
    '목격자 이름과 연락처': { icon: '목격', desc: '본 사람의 이름, 반, 연락 가능 여부, 들은 말의 요지를 정리하세요.' },
    '사건 일지': { icon: '기록', desc: '언제, 어디서, 누가, 무엇을 했는지 시간순으로 짧게 적어두세요.' },
    '녹음 파일 원본': { icon: '녹음', desc: '파일을 편집하지 말고 원본 그대로 보관하고 녹음 날짜를 기록하세요.' },
    '발언 직후 사건 메모': { icon: '메모', desc: '욕설·협박의 정확한 표현, 주변에 있던 사람, 직후 상황을 적어두세요.' },
    '대화 캡처': { icon: '캡처', desc: '보낸 사람, 시간, 앞뒤 대화 맥락이 같이 보이도록 저장하세요.' },
    '반복 상황 일지': { icon: '반복', desc: '따돌림이나 배제가 반복된 날짜와 장소를 표처럼 누적하세요.' },
    '대화방·초대 제외 화면': { icon: '단톡', desc: '초대 제외, 강퇴, 읽씹 강요 등 대화방 상황이 보이게 저장하세요.' },
    '담임 공유 기록': { icon: '공유', desc: '보호자나 담임에게 알린 날짜, 전달한 증거, 받은 답변을 남기세요.' },
    '대화·사진 원본 보관': { icon: '원본', desc: '민감한 자료는 수정하지 말고 보호자나 신뢰할 수 있는 어른과 보관하세요.' },
    '진단서·상담 기록': { icon: '상담', desc: '병원, 상담기관, 학교 상담 일시와 상담 내용을 정리하세요.' },
    '피해 직후 사건 메모': { icon: '메모', desc: '기억이 흐려지기 전에 장소, 행동, 말, 주변 사람을 적어두세요.' },
    '연락·방문 시간 기록': { icon: '시간', desc: '연락이나 찾아온 시간을 날짜별로 모아 반복성을 보여주세요.' },
    '통화·메시지 내역': { icon: '연락', desc: '부재중 전화, 문자, 메신저, 차단 내역을 시간순으로 보관하세요.' },
    '위치·동선 기록': { icon: '위치', desc: '학교, 집 앞, 학원 등 반복 접근 장소와 시간을 따로 정리하세요.' },
    '요구 메시지 캡처': { icon: '요구', desc: '돈이나 물건을 요구한 말, 협박 표현, 시간을 함께 캡처하세요.' },
    '송금·결제 내역': { icon: '송금', desc: '계좌이체, 결제, 현금 전달 날짜와 금액을 확인할 수 있게 보관하세요.' },
    '물건 피해 사진': { icon: '물건', desc: '빼앗기거나 망가진 물건의 사진, 구매 내역, 상태를 남기세요.' },
};

const DEFAULT_EVIDENCE_KEYS = ['음성 녹음', '메시지 캡처', '일지 작성', '목격자 진술'];
const MEASURES = ['서면사과', '접촉금지', '학교봉사', '사회봉사', '특별교육', '출석정지', '학급교체', '전학', '퇴학'];

const riskLevel = (score) => {
    if (score >= 7) return '높음';
    if (score >= 4) return '중간';
    return '낮음';
};

const SShieldResult = () => {
    const [isMenuOpen, setIsMenuOpen] = useState(false);
    const [showConfirm, setShowConfirm] = useState(false);
    const [checked, setChecked] = useState({});
    const [evidenceItems, setEvidenceItems] = useState([]);
    const [report, setReport] = useState(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState('');
    const [params] = useSearchParams();
    const navigate = useNavigate();

    useEffect(() => {
        const id = params.get('id');
        const request = id ? api.get(`/reports/${id}`) : api.get('/reports/latest');

        request
            .then((data) => {
                setReport(data);
                const keys = data.evidence_guide?.length ? data.evidence_guide : DEFAULT_EVIDENCE_KEYS;
                setEvidenceItems(keys.map((key, index) => ({
                    id: `e${index}`,
                    icon: EVIDENCE_MAP[key]?.icon ?? '증거',
                    title: key,
                    desc: EVIDENCE_MAP[key]?.desc ?? '피해 사실을 확인할 수 있는 자료를 원본 형태로 보관하세요.',
                })));
            })
            .catch((e) => {
                setReport(null);
                setError(e.message);
                setEvidenceItems(DEFAULT_EVIDENCE_KEYS.map((key, index) => ({
                    id: `e${index}`,
                    icon: EVIDENCE_MAP[key]?.icon ?? '증거',
                    title: key,
                    desc: EVIDENCE_MAP[key]?.desc ?? '',
                })));
            })
            .finally(() => setLoading(false));
    }, [params]);

    const toggleCheck = (id) => setChecked((prev) => ({ ...prev, [id]: !prev[id] }));
    const checkedCount = Object.values(checked).filter(Boolean).length;

    const score = report?.risk_score ?? 0;
    const cx = 80;
    const cy = 90;
    const r = 60;
    const angle = (score / 10) * 180;
    const rad = (Math.PI * angle) / 180;
    const x = cx + r * Math.cos(Math.PI - rad);
    const y = cy - r * Math.sin(Math.PI - rad);
    const lawWidths = (report?.law_relevance_scores ?? []).map((s) => Math.round(s * 100));
    const rawRange = report?.expected_measure_range ?? [0, 3];
    const mStart = Math.max(0, Math.min(MEASURES.length - 1, rawRange[0] ?? 0));
    const mEnd = Math.max(mStart, Math.min(MEASURES.length - 1, rawRange[1] ?? 3));
    const COL_W = 31;
    const measureX = 15;
    const measureWidth = (mEnd + 1) * COL_W;
    const measureRangeText = `${mStart + 1}호(${MEASURES[mStart]})부터 ${mEnd + 1}호(${MEASURES[mEnd]})까지 가능성이 있습니다.`;

    const logout = () => {
        clearSession();
        navigate('/', { replace: true });
    };

    return (
        <div className="ss-wrap">
            <aside
                className={`ss-sidebar ${isMenuOpen ? 'open' : ''}`}
                onMouseEnter={() => setIsMenuOpen(true)}
                onMouseLeave={() => setIsMenuOpen(false)}
            >
                <div className="ss-sidebar-container">
                    {!isMenuOpen && (
                        <div className="ss-menu-btn">
                            <div className="ss-menu-circle"><span></span><span></span><span></span></div>
                        </div>
                    )}
                    <div className={`ss-menu-content ${isMenuOpen ? 'visible' : ''}`}>
                        <nav className="ss-nav-list">
                            <div className="ss-nav-item active"><span className="ss-emoji-icon">R</span><span className="ss-nav-text">분석 결과</span></div>
                            <div className="ss-nav-item" onClick={() => navigate('/mypage')}><span className="ss-emoji-icon">M</span><span className="ss-nav-text">마이페이지</span></div>
                            <div className="ss-nav-item" onClick={() => navigate('/chat')}><span className="ss-emoji-icon">C</span><span className="ss-nav-text">상담</span></div>
                        </nav>
                        <SessionHistory />
                        <div className="ss-logout-section">
                            <div className="ss-divider"></div>
                            <button className="ss-logout-btn" onClick={() => setShowConfirm(true)}>로그아웃</button>
                        </div>
                    </div>
                </div>
            </aside>

            <main className="ss-main">
                <header className="ss-header">
                    <div className="ss-logo">S-<span className="logo-accent">Shield</span></div>
                    <nav className="ss-mobile-nav" aria-label="모바일 메뉴">
                        <button onClick={() => navigate('/chat')}>상담</button>
                        <button className="active">결과</button>
                        <button onClick={() => navigate('/mypage')}>마이</button>
                        <button onClick={() => navigate('/mypage#session-history')}>기록</button>
                    </nav>
                    <button className="ss-back-btn" onClick={() => navigate('/chat')}>상담으로 돌아가기</button>
                </header>

                <div className="ss-content-scroll">
                    <div className="ss-container">
                        <h1 className="ss-report-title">S-Shield 분석 결과 리포트</h1>

                        {loading ? (
                            <p style={{ color: '#aaa', textAlign: 'center' }}>리포트를 불러오는 중...</p>
                        ) : !report ? (
                            <div className="ss-summary-card">
                                <p className="ss-summary-text">
                                    {error || '아직 생성된 리포트가 없습니다. 상담을 진행한 뒤 리포트를 생성해 주세요.'}
                                </p>
                                <button className="ss-inline-btn" onClick={() => navigate('/chat')}>
                                    상담 시작하기
                                </button>
                            </div>
                        ) : (
                            <>
                                <div className="ss-summary-card">
                                    <span className="ss-summary-badge">{report.assessment_status || 'AI 요약'}</span>
                                    <p className="ss-summary-text">{report.summary}</p>
                                </div>

                                <div className="ss-grid">
                                    <div className="ss-card">
                                        <p className="ss-label">핵심 판단 근거</p>
                                        <ul className="ss-report-list">
                                            {(report.assessment_details || []).map((item) => (
                                                <li key={item}>{item}</li>
                                            ))}
                                        </ul>
                                    </div>
                                    <div className="ss-card">
                                        <p className="ss-label">우선 권장 조치</p>
                                        <ul className="ss-report-list">
                                            {(report.recommended_actions || []).map((item) => (
                                                <li key={item}>{item}</li>
                                            ))}
                                        </ul>
                                    </div>
                                </div>

                                <div className="ss-card ss-risk-card">
                                    <div className="ss-gauge-container">
                                        <svg viewBox="0 0 160 130" width="160" height="130">
                                            <path d="M 20 90 A 60 60 0 0 1 140 90" stroke="#3A3A3A" strokeWidth="12" fill="none" strokeLinecap="round" />
                                            <path d={`M 20 90 A 60 60 0 0 1 ${x} ${y}`} stroke="#7e74f0" strokeWidth="12" fill="none" strokeLinecap="round" />
                                            <circle cx="80" cy="90" r="4" fill="#FEFEFE" />
                                            <text x="80" y="115" textAnchor="middle" fill="#FEFEFE" fontSize="16" fontWeight="900">
                                                {score}<tspan fontSize="10" fill="#888">/10</tspan>
                                            </text>
                                            <text x="80" y="128" textAnchor="middle" fill="#7e74f0" fontSize="11" fontWeight="700">
                                                {riskLevel(score)}
                                            </text>
                                        </svg>
                                    </div>
                                    <div>
                                        <p className="ss-danger">위험도 {riskLevel(score)}</p>
                                        <p className="ss-risk-desc">분석된 유형: {(report.violence_types || []).join(', ') || '분류 정보 없음'}</p>
                                    </div>
                                </div>

                                <h2 className="ss-sub-title">상황 분석</h2>
                                <div className="ss-grid">
                                    <div className="ss-card">
                                        <p className="ss-label">관련 법령</p>
                                        <div className="ss-law-list">
                                            {(report.matched_laws || []).map((law, i) => (
                                                <div className="ss-law-item" key={law}>
                                                    <p>{law}</p>
                                                    <div className="ss-bar-bg">
                                                        <div className="ss-bar-fill" style={{ width: `${lawWidths[i] ?? 50}%` }} />
                                                    </div>
                                                </div>
                                            ))}
                                        </div>
                                    </div>

                                    <div className="ss-card">
                                        <p className="ss-label">예상 조치 범위</p>
                                        <svg viewBox="0 0 280 85">
                                            <defs>
                                                <linearGradient id="grad" x1="0%" y1="0%" x2="100%">
                                                    <stop offset="0%" stopColor="#3a3050" />
                                                    <stop offset="100%" stopColor="#5f52d0" />
                                                </linearGradient>
                                            </defs>
                                            <rect x={measureX} y="5" width={measureWidth} height="50" fill="url(#grad)" />
                                            <line x1="15" y1="30" x2="265" y2="30" stroke="rgba(255,255,255,0.45)" strokeWidth="0.8" />
                                            {MEASURES.map((_, i) => (
                                                <line key={i} x1={15 + i * 31} y1="5" x2={15 + i * 31} y2="55" stroke="rgba(255,255,255,0.45)" strokeWidth="0.8" />
                                            ))}
                                            {MEASURES.map((measure, i) => (
                                                <text key={measure} x={15 + i * 31} y="72" textAnchor="middle" fill="#fff" fontSize="8">{i + 1}</text>
                                            ))}
                                        </svg>
                                        <p className="ss-risk-desc">{measureRangeText}</p>
                                    </div>
                                </div>

                                <h2 className="ss-sub-title">폭력 유형 분류</h2>
                                <div className="ss-card ss-center">
                                    <p className="ss-ai">AI가 분석한 해당 유형</p>
                                    <div className="ss-tags">
                                        {(report.violence_types || []).map((type) => (
                                            <span key={type} className="tag p">{type}</span>
                                        ))}
                                    </div>
                                </div>

                                <h2 className="ss-sub-title">증거 수집 가이드</h2>
                                <div className="ss-card">
                                    <div className="ss-evidence-header">
                                        <p className="ss-ai" style={{ margin: 0 }}>상황에 맞는 증거를 최대한 원본 형태로 보관하세요.</p>
                                        <span className="ss-check-progress">{checkedCount} / {evidenceItems.length} 완료</span>
                                    </div>
                                    <div className="ss-checklist">
                                        {evidenceItems.map((item) => (
                                            <div key={item.id} className={`check-item ${checked[item.id] ? 'checked' : ''}`} onClick={() => toggleCheck(item.id)}>
                                                <div className="check-box">{checked[item.id] && <span className="check-mark">✓</span>}</div>
                                                <div className="check-content"><strong>{item.icon} {item.title}</strong><p>{item.desc}</p></div>
                                            </div>
                                        ))}
                                    </div>
                                </div>

                                <h2 className="ss-sub-title">다음 행동</h2>
                                <div className="ss-actions">
                                    <a href="tel:117" className="ss-action-link"><button>117 학교폭력 신고</button></a>
                                    <button onClick={() => navigate('/chat')}>상담 이어가기</button>
                                </div>
                            </>
                        )}
                    </div>
                </div>
            </main>

            {showConfirm && (
                <div className="confirm-overlay" onClick={() => setShowConfirm(false)}>
                    <div className="confirm-box" onClick={(e) => e.stopPropagation()}>
                        <p>로그아웃하시겠습니까?</p>
                        <div className="confirm-btns">
                            <button className="confirm-btn-cancel" onClick={() => setShowConfirm(false)}>취소</button>
                            <button className="confirm-btn-ok" onClick={logout}>로그아웃</button>
                        </div>
                    </div>
                </div>
            )}
        </div>
    );
};

export default SShieldResult;
