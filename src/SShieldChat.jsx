import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import './SShieldChat.css';
import { api, clearSession, getSession, setSession } from './api.js';
import SessionHistory from './SessionHistory.jsx';

const EXAMPLE_QUESTIONS = [
    '친구들이 단체 채팅방에서 계속 욕하고 놀립니다.',
    '학교에서 맞았고 멍이 들었는데 어떻게 해야 하나요?',
    'SNS에 제 사진과 비방 글이 올라왔습니다.',
];

const initialMessage = () => ({
    id: 'welcome',
    type: 'ai',
    text: '안녕하세요. S-Shield 법률 상담 AI입니다.\n상황을 자세히 적어주시면 학교폭력 유형, 관련 법령, 증거 준비 방법을 정리해드릴게요.',
    time: now(),
});

const removeInlineDisclaimer = (text = '') => text
    .split('\n')
    .filter((line) => !(line.includes('일반적인 법률 정보') && line.includes('대체하지')))
    .join('\n')
    .trim();

function now() {
    return new Date().toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit' });
}

function renderInlineMarkdown(text) {
    const parts = String(text).split(/(\*\*[^*]+\*\*|`[^`]+`)/g);
    return parts.map((part, index) => {
        if (part.startsWith('**') && part.endsWith('**')) {
            return <strong key={index}>{part.slice(2, -2)}</strong>;
        }
        if (part.startsWith('`') && part.endsWith('`')) {
            return <code key={index}>{part.slice(1, -1)}</code>;
        }
        return <React.Fragment key={index}>{part}</React.Fragment>;
    });
}

function MarkdownMessage({ text }) {
    const lines = String(text || '').split('\n');
    const blocks = [];

    for (let i = 0; i < lines.length; i += 1) {
        const raw = lines[i];
        const line = raw.trim();
        if (!line) continue;

        const heading = /^(#{1,3})\s+(.+)$/.exec(line);
        if (heading) {
            blocks.push(<h4 key={`h-${i}`}>{renderInlineMarkdown(heading[2])}</h4>);
            continue;
        }

        if (/^([-*•])\s+/.test(line)) {
            const items = [];
            let j = i;
            while (j < lines.length && /^([-*•])\s+/.test(lines[j].trim())) {
                items.push(lines[j].trim().replace(/^([-*•])\s+/, ''));
                j += 1;
            }
            blocks.push(
                <ul key={`ul-${i}`}>
                    {items.map((item, idx) => <li key={idx}>{renderInlineMarkdown(item)}</li>)}
                </ul>
            );
            i = j - 1;
            continue;
        }

        if (/^\d+[.)]\s+/.test(line)) {
            const items = [];
            let j = i;
            while (j < lines.length && /^\d+[.)]\s+/.test(lines[j].trim())) {
                items.push(lines[j].trim().replace(/^\d+[.)]\s+/, ''));
                j += 1;
            }
            blocks.push(
                <ol key={`ol-${i}`}>
                    {items.map((item, idx) => <li key={idx}>{renderInlineMarkdown(item)}</li>)}
                </ol>
            );
            i = j - 1;
            continue;
        }

        const paragraph = [line];
        let j = i + 1;
        while (
            j < lines.length
            && lines[j].trim()
            && !/^(#{1,3})\s+/.test(lines[j].trim())
            && !/^([-*•])\s+/.test(lines[j].trim())
            && !/^\d+[.)]\s+/.test(lines[j].trim())
        ) {
            paragraph.push(lines[j].trim());
            j += 1;
        }
        blocks.push(<p key={`p-${i}`}>{renderInlineMarkdown(paragraph.join(' '))}</p>);
        i = j - 1;
    }

    return <div className="markdown-message">{blocks}</div>;
}

function hasTopicShiftHint(content) {
    const text = content.trim();
    return [
        /다른\s*(사건|얘기|상담|내용|상황)/,
        /새\s*(사건|상담|내용|상황)/,
        /별개/,
        /이번(?:엔|에는)\s*(다른|내가|제가|친구가)/,
        /(내가|제가)\s*(때렸|욕했|올렸|괴롭혔|가해)/,
    ].some((pattern) => pattern.test(text));
}

const TOPIC_STOP_WORDS = new Set([
    '그리고', '근데', '그런데', '그래서', '저는', '제가', '내가', '나는', '친구',
    '상대', '상대방', '학교', '오늘', '어제', '계속', '그냥', '진짜', '너무',
    '어떻게', '해야', '하나요', '있어요', '했어요', '당했어요'
]);

const TOPIC_GROUPS = [
    { name: 'cyber', patterns: [/sns/i, /카톡/, /단톡/, /채팅/, /dm/i, /인스타/, /게시/, /댓글/, /사진/, /온라인/] },
    { name: 'verbal', patterns: [/욕/, /모욕/, /비방/, /협박/, /소문/, /놀림/, /명예훼손/] },
    { name: 'physical', patterns: [/때렸/, /맞았/, /폭행/, /밀쳤/, /발로/, /주먹/, /상처/, /멍/] },
    { name: 'exclusion', patterns: [/따돌/, /왕따/, /무시/, /소외/, /배제/] },
    { name: 'sexual', patterns: [/성추행/, /성희롱/, /성적/, /몸을/, /수치심/] },
    { name: 'money', patterns: [/돈/, /갈취/, /빼앗/, /내놔/, /물건/, /강요/] },
    { name: 'stalking', patterns: [/스토킹/, /따라/, /기다리/, /집 앞/, /계속 연락/] },
    { name: 'self', patterns: [/내가/, /제가/, /나도/, /제가 먼저/, /내가 먼저/, /사과/, /올렸/, /때렸/, /욕했/] },
    { name: 'other_person', patterns: [/동생/, /부모/, /선생/, /담임/, /선배/, /후배/, /친구가/, /아이/] },
];

const FOLLOW_UP_PATTERNS = [
    /같은\s*반/, /반\s*친구/, /동급생/, /선배/, /후배/, /학생/,
    /어제|오늘|방금|지난|부터|계속|반복|매일|몇\s*번|한\s*번/,
    /캡처|증거|녹음|사진|목격|진단서|메시지|URL|url/,
    /네|아니요|맞아요|아마|모르겠|기억/
];

function normalizeTopicText(content) {
    return String(content || '').toLowerCase().replace(/[^\p{L}\p{N}\s]/gu, ' ');
}

function hasExplicitTopicShiftHint(content) {
    const text = normalizeTopicText(content);
    return hasTopicShiftHint(content) || [
        /다른\s*(사건|얘기|상담|내용|상황|문제)/,
        /새\s*(사건|상담|얘기|내용|문제)/,
        /별개/,
        /이번(?:엔|에는)\s*(다른|제가|내가|친구가|동생이|선배가|후배가)/,
    ].some((pattern) => pattern.test(text));
}

function detectTopicGroups(content) {
    const text = normalizeTopicText(content);
    return new Set(TOPIC_GROUPS
        .filter((group) => group.patterns.some((pattern) => pattern.test(text)))
        .map((group) => group.name));
}

function extractTopicTokens(content) {
    return new Set(normalizeTopicText(content)
        .split(/\s+/)
        .map((token) => token.trim())
        .filter((token) => token.length >= 2 && !TOPIC_STOP_WORDS.has(token))
        .slice(0, 40));
}

function intersects(a, b) {
    return [...a].some((item) => b.has(item));
}

function isLikelyFollowUpAnswer(content) {
    const text = normalizeTopicText(content);
    return text.length <= 28 || FOLLOW_UP_PATTERNS.some((pattern) => pattern.test(text));
}

function hasMeaningfulTopicShift(content, previousUserMessages) {
    if (!previousUserMessages.length) return false;

    const previousText = previousUserMessages.join(' ');
    const previousGroups = detectTopicGroups(previousText);
    const currentGroups = detectTopicGroups(content);

    if (previousGroups.size > 0 && currentGroups.size > 0 && !intersects(previousGroups, currentGroups)) {
        return true;
    }

    if (isLikelyFollowUpAnswer(content)) return false;

    const previousTokens = extractTopicTokens(previousText);
    const currentTokens = extractTopicTokens(content);
    if (previousTokens.size < 3 || currentTokens.size < 3) return false;

    const sharedCount = [...currentTokens].filter((token) => previousTokens.has(token)).length;
    const overlapRatio = sharedCount / Math.min(previousTokens.size, currentTokens.size);
    return overlapRatio < 0.2;
}

const toUiMessage = (message) => ({
    id: message.id,
    type: message.role === 'assistant' ? 'ai' : 'user',
    text: message.role === 'assistant' ? removeInlineDisclaimer(message.content) : message.content,
    time: message.created_at
        ? new Date(message.created_at).toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit' })
        : now(),
});

const SShieldChat = () => {
    const initialSessionIdRef = useRef(getSession());
    const initialConversationKey = initialSessionIdRef.current
        ? `session:${initialSessionIdRef.current}`
        : `draft:${Date.now()}`;
    const [isMenuOpen, setIsMenuOpen] = useState(false);
    const [isLoggingOut, setIsLoggingOut] = useState(false);
    const [showConfirm, setShowConfirm] = useState(false);
    const [topicPrompt, setTopicPrompt] = useState(null);
    const [messages, setMessages] = useState([initialMessage()]);
    const [sessions, setSessions] = useState([]);
    const [input, setInput] = useState('');
    const [sessionId, setSessionId] = useState(initialSessionIdRef.current);
    const [conversationKey, setConversationKey] = useState(initialConversationKey);
    const [pendingKeys, setPendingKeys] = useState(() => new Set());
    const [showReport, setShowReport] = useState(false);
    const [generatingReport, setGeneratingReport] = useState(false);
    const scrollRef = useRef(null);
    const typingTimerRef = useRef(null);
    const sessionIdRef = useRef(initialSessionIdRef.current);
    const conversationKeyRef = useRef(initialConversationKey);
    const pendingKeysRef = useRef(new Set());
    const messageLoadSequenceRef = useRef(0);
    const shouldAutoScrollRef = useRef(true);
    const navigate = useNavigate();
    const isCurrentLoading = pendingKeys.has(conversationKey);

    const activateConversation = useCallback((id, key = `session:${id}`) => {
        sessionIdRef.current = id;
        conversationKeyRef.current = key;
        setSessionId(id);
        setConversationKey(key);
        if (id) setSession(id);
    }, []);

    const loadSessions = useCallback(async () => {
        try {
            const items = await api.get('/chat/sessions');
            setSessions(items);
            return items;
        } catch {
            setSessions([]);
            return [];
        }
    }, []);

    const loadMessagesForSession = useCallback(async (id) => {
        const loadSequence = ++messageLoadSequenceRef.current;
        activateConversation(id);
        try {
            const items = await api.get(`/chat/sessions/${id}/messages`);
            if (loadSequence !== messageLoadSequenceRef.current) return;
            const uiMessages = items.length ? items.map(toUiMessage) : [initialMessage()];
            setMessages(uiMessages);
            const readiness = await api.get(`/chat/sessions/${id}/readiness`);
            if (loadSequence !== messageLoadSequenceRef.current) return;
            setShowReport(Boolean(readiness.ready));
        } catch {
            if (loadSequence !== messageLoadSequenceRef.current) return;
            localStorage.removeItem('ss_session');
            const draftKey = `draft:${Date.now()}`;
            activateConversation(null, draftKey);
            setMessages([initialMessage()]);
            setShowReport(false);
        }
    }, [activateConversation]);

    useEffect(() => {
        const savedSessionId = getSession();
        loadSessions().then((items) => {
            if (savedSessionId) {
                loadMessagesForSession(savedSessionId);
            } else if (items.length > 0) {
                loadMessagesForSession(items[0].session_id);
            }
        });

        return () => {
            if (typingTimerRef.current) clearInterval(typingTimerRef.current);
        };
    }, [loadMessagesForSession, loadSessions]);

    useEffect(() => {
        if (scrollRef.current && shouldAutoScrollRef.current) {
            scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
        }
    }, [messages]);

    const handleScroll = () => {
        const el = scrollRef.current;
        if (!el) return;
        const distanceFromBottom = el.scrollHeight - el.scrollTop - el.clientHeight;
        shouldAutoScrollRef.current = distanceFromBottom < 80;
    };

    const disableAutoScrollWhileReading = () => {
        if (isCurrentLoading) {
            shouldAutoScrollRef.current = false;
        }
    };

    const appendTypingReply = (reply) => {
        const id = `a-${Date.now()}`;
        const safeReply = removeInlineDisclaimer(reply)
            || '답변을 생성하지 못했습니다. 다시 시도해 주세요.';
        let index = 0;

        setMessages((prev) => [...prev, { id, type: 'ai', text: '', time: now() }]);
        if (typingTimerRef.current) clearInterval(typingTimerRef.current);

        typingTimerRef.current = setInterval(() => {
            index += 2;
            setMessages((prev) => prev.map((message) => (
                message.id === id ? { ...message, text: safeReply.slice(0, index) } : message
            )));

            if (index >= safeReply.length) {
                clearInterval(typingTimerRef.current);
                typingTimerRef.current = null;
            }
        }, 16);
    };

    const shouldConfirmTopic = (content) => {
        const previousUserMessages = messages
            .filter((message) => message.type === 'user')
            .map((message) => message.text || '');

        return Boolean(sessionIdRef.current)
            && previousUserMessages.length > 0
            && (
                hasExplicitTopicShiftHint(content)
                || hasMeaningfulTopicShift(content, previousUserMessages)
            );
    };

    const sendOrAskTopic = (text) => {
        const content = (text || input).trim();
        if (!content || isCurrentLoading) return;

        if (shouldConfirmTopic(content)) {
            setTopicPrompt(content);
            setInput('');
            return;
        }

        handleSend(content);
    };

    const handleSend = async (text, options = {}) => {
        const content = (text || input).trim();
        const targetKey = options.forceNewSession ? `draft:${Date.now()}` : conversationKeyRef.current;
        if (!content || pendingKeysRef.current.has(targetKey)) return;
        const targetSessionId = options.forceNewSession ? null : sessionIdRef.current;

        setInput('');
        shouldAutoScrollRef.current = true;
        if (options.forceNewSession) {
            messageLoadSequenceRef.current += 1;
            localStorage.removeItem('ss_session');
            activateConversation(null, targetKey);
            setShowReport(false);
        }
        setMessages((prev) => [
            ...(options.forceNewSession ? [] : prev),
            { id: `u-${Date.now()}`, type: 'user', text: content, time: now() },
        ]);
        pendingKeysRef.current.add(targetKey);
        setPendingKeys((prev) => {
            const next = new Set(prev);
            next.add(targetKey);
            return next;
        });

        try {
            const data = await api.post('/chat/message', { sessionId: targetSessionId, content });
            if (conversationKeyRef.current === targetKey) {
                if (targetSessionId === null) {
                    activateConversation(data.session_id);
                }
                setShowReport(Boolean(data.report_ready));
                appendTypingReply(data.reply);
            }
            loadSessions();
        } catch (e) {
            if (conversationKeyRef.current === targetKey) {
                setMessages((prev) => [...prev, {
                    id: `e-${Date.now()}`,
                type: 'ai',
                text: e.message || '오류가 발생했습니다. 잠시 후 다시 시도해 주세요.',
                time: now(),
            }]);
            }
        } finally {
            pendingKeysRef.current.delete(targetKey);
            setPendingKeys((prev) => {
                const next = new Set(prev);
                next.delete(targetKey);
                return next;
            });
        }
    };

    const handleGenerateReport = async () => {
        if (!sessionId) return;
        setGeneratingReport(true);
        try {
            const report = await api.post('/reports/generate', { sessionId, title: '' });
            navigate(`/result?id=${report.id}`);
        } catch (e) {
            alert(e.message);
        } finally {
            setGeneratingReport(false);
        }
    };

    const handleNewChat = () => {
        messageLoadSequenceRef.current += 1;
        localStorage.removeItem('ss_session');
        activateConversation(null, `draft:${Date.now()}`);
        shouldAutoScrollRef.current = true;
        setShowReport(false);
        setMessages([initialMessage()]);
        setInput('');
    };

    const handleLogout = () => {
        setIsLoggingOut(true);
        setTimeout(() => {
            clearSession();
            navigate('/', { replace: true });
        }, 700);
    };

    return (
        <div className={`ss-chat-root ${isLoggingOut ? 'logging-out' : ''}`}>
            <aside
                className={`ss-sidebar ${isMenuOpen ? 'open' : ''}`}
                onMouseEnter={() => setIsMenuOpen(true)}
                onMouseLeave={() => setIsMenuOpen(false)}
            >
                <div className="ss-sidebar-container">
                    {!isMenuOpen && (
                        <div className="ss-menu-btn">
                            <div className="ss-menu-circle">
                                <span></span><span></span><span></span>
                            </div>
                        </div>
                    )}
                    <div className={`ss-menu-content ${isMenuOpen ? 'visible' : ''}`}>
                        <nav className="ss-nav-list">
                            <div className="ss-nav-item" onClick={() => navigate('/result')}>
                                <span className="ss-emoji-icon">R</span>
                                <span className="ss-nav-text">분석 결과</span>
                            </div>
                            <div className="ss-nav-item" onClick={() => navigate('/mypage')}>
                                <span className="ss-emoji-icon">M</span>
                                <span className="ss-nav-text">마이페이지</span>
                            </div>
                            <div className="ss-nav-item active">
                                <span className="ss-emoji-icon">C</span>
                                <span className="ss-nav-text">상담</span>
                            </div>
                        </nav>

                        <SessionHistory
                            sessions={sessions}
                            activeSessionId={sessionId}
                            onSelect={loadMessagesForSession}
                        />

                        <div className="ss-logout-section">
                            <div className="ss-divider"></div>
                            <button className="ss-side-action-btn" onClick={handleNewChat}>새 상담</button>
                            <button className="ss-logout-btn" onClick={() => setShowConfirm(true)}>로그아웃</button>
                        </div>
                    </div>
                </div>
            </aside>

            <main className="ss-chat-main">
                <header className="ss-chat-header">
                    <div className="ss-chat-logo">S-<span className="logo-accent">Shield</span></div>
                    <nav className="ss-mobile-nav" aria-label="모바일 메뉴">
                        <button className="active">상담</button>
                        <button onClick={() => navigate('/result')}>결과</button>
                        <button onClick={() => navigate('/mypage')}>마이</button>
                        <button onClick={() => navigate('/mypage#session-history')}>기록</button>
                        <button onClick={handleNewChat}>새 상담</button>
                    </nav>
                    <div className="ss-header-line"></div>
                </header>

                <div
                    className="ss-chat-content"
                    ref={scrollRef}
                    onScroll={handleScroll}
                    onWheel={disableAutoScrollWhileReading}
                    onTouchStart={disableAutoScrollWhileReading}
                    onPointerDown={disableAutoScrollWhileReading}
                >
                    {messages.map((msg) => (
                        <div key={msg.id} className={`ss-msg-row ${msg.type}`}>
                            <div className="ss-msg-col">
                                <div className="ss-msg-bubble">
                                    {msg.type === 'ai'
                                        ? <MarkdownMessage text={msg.text} />
                                        : (msg.text || '').split('\n').map((line, i, arr) => (
                                            <span key={i}>{line}{i < arr.length - 1 && <br />}</span>
                                        ))}
                                </div>
                                <span className="msg-time">{msg.time}</span>
                            </div>
                        </div>
                    ))}
                    {isCurrentLoading && (
                        <div className="ss-msg-row ai">
                            <div className="ss-msg-col">
                                <div className="typing-indicator" aria-label="답변 생성 중">
                                    <span className="typing-dot"></span>
                                    <span className="typing-dot"></span>
                                    <span className="typing-dot"></span>
                                </div>
                            </div>
                        </div>
                    )}
                </div>

                <footer className="ss-chat-footer">
                    <div className="example-chips">
                        {EXAMPLE_QUESTIONS.map((q) => (
                            <button key={q} className="example-chip" onClick={() => sendOrAskTopic(q)} disabled={isCurrentLoading}>
                                {q}
                            </button>
                        ))}
                    </div>

                    {showReport && (
                        <div className="chat-cta">
                            <p>핵심 정보가 확인되었습니다. 분석 리포트를 생성해 확인해 보세요.</p>
                            <button className="chat-cta-btn" onClick={handleGenerateReport} disabled={generatingReport}>
                                {generatingReport ? '생성 중...' : '리포트 보기'}
                            </button>
                        </div>
                    )}

                    <div className="ss-input-box">
                        <input
                            type="text"
                            placeholder="상황을 입력해 주세요"
                            value={input}
                            onChange={(e) => setInput(e.target.value)}
                            onKeyDown={(e) => e.key === 'Enter' && sendOrAskTopic()}
                        />
                        <button className="ss-send-btn" onClick={() => sendOrAskTopic()} disabled={isCurrentLoading}>
                            보내기
                        </button>
                    </div>
                    <p className="ss-legal-notice">
                        이 정보는 일반적인 법률 정보이며 전문 법률상담을 대체하지 않습니다.
                    </p>
                </footer>
            </main>

            {isLoggingOut && (
                <div className="logout-overlay">
                    <div className="logout-spinner"></div>
                    <p>로그아웃 중입니다...</p>
                </div>
            )}

            {showConfirm && (
                <div className="confirm-overlay" onClick={() => setShowConfirm(false)}>
                    <div className="confirm-box" onClick={(e) => e.stopPropagation()}>
                        <p>로그아웃하시겠습니까?</p>
                        <div className="confirm-btns">
                            <button className="confirm-btn-cancel" onClick={() => setShowConfirm(false)}>취소</button>
                            <button className="confirm-btn-ok" onClick={() => { setShowConfirm(false); handleLogout(); }}>로그아웃</button>
                        </div>
                    </div>
                </div>
            )}

            {topicPrompt && (
                <div className="confirm-overlay" onClick={() => setTopicPrompt(null)}>
                    <div className="confirm-box topic-confirm-box" onClick={(e) => e.stopPropagation()}>
                        <p>이 내용도 같은 가해자와 같은 사건에서 이어진 일인가요?</p>
                        <span className="topic-confirm-desc">
                            같은 가해자와 같은 사건이면 현재 상담에 추가하고, 다른 사안이면 새 상담으로 분리해서 리포트가 섞이지 않게 저장합니다.
                        </span>
                        <div className="confirm-btns topic-confirm-btns">
                            <button
                                className="confirm-btn-cancel"
                                onClick={() => {
                                    const content = topicPrompt;
                                    setTopicPrompt(null);
                                    handleSend(content);
                                }}
                            >
                                예, 같은 사안에 추가
                            </button>
                            <button
                                className="confirm-btn-ok"
                                onClick={() => {
                                    const content = topicPrompt;
                                    setTopicPrompt(null);
                                    handleSend(content, { forceNewSession: true });
                                }}
                            >
                                아니요, 새 상담으로 시작
                            </button>
                        </div>
                    </div>
                </div>
            )}
        </div>
    );
};

export default SShieldChat;
