import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import './SShieldChat.css';
import { api, clearSession, hasToken, setSession } from './api.js';
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

const normalizeConfirmationPrompts = (prompts = []) => (Array.isArray(prompts) ? prompts : [])
    .map((prompt) => ({
        id: prompt.id || prompt.question,
        purpose: String(prompt.purpose || '').trim(),
        question: String(prompt.question || '').trim(),
        instruction: String(prompt.instruction || '').trim(),
        options: (Array.isArray(prompt.options) ? prompt.options : [])
            .map((option) => ({
                label: String(option.label || '').trim(),
                message: String(option.message || option.label || '').trim(),
            }))
            .filter((option) => option.label && option.message),
    }))
    .filter((prompt) => prompt.question && prompt.options.length);

const clearConfirmationPrompts = (items) => items.map((message) => (
    message.confirmationPrompts?.length ? { ...message, confirmationPrompts: [] } : message
));

const attachPromptsToLatestAiMessage = (items, prompts) => {
    const normalized = normalizeConfirmationPrompts(prompts);
    if (!normalized.length) return clearConfirmationPrompts(items);

    const next = clearConfirmationPrompts(items);
    for (let i = next.length - 1; i >= 0; i -= 1) {
        if (next[i].type === 'ai') {
            next[i] = { ...next[i], confirmationPrompts: normalized };
            return next;
        }
    }
    return next;
};

const confirmationPromptKey = (messageId, prompt, promptIndex) => (
    `${messageId}:${prompt.id || prompt.question || 'prompt'}:${promptIndex}`
);

const confirmationOptionKey = (option) => `${option.label || ''}:${option.message || ''}`;

const isDirectConfirmationOption = (option) => (
    option.label === '직접 입력' || String(option.message || '').trim().endsWith(':')
);

const selectedConfirmationOptions = (draft) => {
    if (!draft) return [];
    if (Array.isArray(draft.selectedOptions)) return draft.selectedOptions;
    if (draft.message) {
        return [{
            label: draft.label,
            message: draft.message,
            direct: Boolean(draft.direct),
        }];
    }
    return [];
};

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

const toGuestHistoryPayload = (items) => items
    .filter((message) => message.id !== 'welcome')
    .filter((message) => message.type === 'user' || message.type === 'ai')
    .map((message) => ({
        role: message.type === 'ai' ? 'assistant' : 'user',
        content: String(message.text || '').trim(),
    }))
    .filter((message) => message.content)
    .slice(-24);

const SShieldChat = () => {
    const initialSessionIdRef = useRef(null);
    const initialConversationKey = `draft:${Date.now()}`;
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
    const [confirmationDrafts, setConfirmationDrafts] = useState({});
    const [showReport, setShowReport] = useState(false);
    const [generatingReport, setGeneratingReport] = useState(false);
    const [isSessionLoading, setIsSessionLoading] = useState(false);
    const [isSessionsLoading, setIsSessionsLoading] = useState(false);
    const [isGuestMode, setIsGuestMode] = useState(() => !hasToken());
    const scrollRef = useRef(null);
    const inputRef = useRef(null);
    const typingTimerRef = useRef(null);
    const sessionIdRef = useRef(initialSessionIdRef.current);
    const conversationKeyRef = useRef(initialConversationKey);
    const pendingKeysRef = useRef(new Set());
    const messageLoadSequenceRef = useRef(0);
    const shouldAutoScrollRef = useRef(true);
    const navigate = useNavigate();
    const [searchParams] = useSearchParams();
    const requestedSessionParam = searchParams.get('session');
    const isGuest = isGuestMode;
    const isCurrentLoading = pendingKeys.has(conversationKey);
    const isChatBusy = isCurrentLoading || isSessionLoading;

    const requireLoginForSavedFeature = () => {
        alert('상담 기록과 리포트는 로그인 후 이용할 수 있습니다.');
        navigate('/login');
    };

    const activateConversation = useCallback((id, key = `session:${id}`) => {
        sessionIdRef.current = id;
        conversationKeyRef.current = key;
        setSessionId(id);
        setConversationKey(key);
        if (id) setSession(id);
    }, []);

    const loadSessions = useCallback(async () => {
        if (isGuestMode || !hasToken()) {
            setSessions([]);
            setIsSessionsLoading(false);
            return [];
        }
        setIsSessionsLoading(true);
        try {
            const items = await api.get('/chat/sessions');
            setSessions(items);
            return items;
        } catch {
            setSessions([]);
            return [];
        } finally {
            setIsSessionsLoading(false);
        }
    }, [isGuestMode]);

    const loadMessagesForSession = useCallback(async (id) => {
        if (isGuestMode || !hasToken()) {
            const draftKey = `draft:${Date.now()}`;
            localStorage.removeItem('ss_session');
            activateConversation(null, draftKey);
            setMessages([initialMessage()]);
            setShowReport(false);
            return;
        }
        const loadSequence = ++messageLoadSequenceRef.current;
        activateConversation(id);
        setIsSessionLoading(true);
        setConfirmationDrafts({});
        setShowReport(false);
        shouldAutoScrollRef.current = true;
        if (typingTimerRef.current) {
            clearInterval(typingTimerRef.current);
            typingTimerRef.current = null;
        }
        try {
            const detail = await api.get(`/chat/sessions/${id}`);
            if (loadSequence !== messageLoadSequenceRef.current) return;
            const items = Array.isArray(detail.messages) ? detail.messages : [];
            const readiness = detail.readiness || {};
            const uiMessages = items.length ? items.map(toUiMessage) : [initialMessage()];
            setMessages(attachPromptsToLatestAiMessage(uiMessages, readiness.confirmation_prompts));
            setShowReport(Boolean(readiness.ready));
        } catch {
            if (loadSequence !== messageLoadSequenceRef.current) return;
            localStorage.removeItem('ss_session');
            const draftKey = `draft:${Date.now()}`;
            activateConversation(null, draftKey);
            setMessages([initialMessage()]);
            setShowReport(false);
        } finally {
            if (loadSequence === messageLoadSequenceRef.current) {
                setIsSessionLoading(false);
            }
        }
    }, [activateConversation, isGuestMode]);

    useEffect(() => {
        if (!hasToken()) {
            setIsGuestMode(true);
            return;
        }

        let alive = true;
        api.get('/auth/me')
            .then(() => {
                if (alive) setIsGuestMode(false);
            })
            .catch(() => {
                clearSession();
                if (alive) setIsGuestMode(true);
            });

        return () => {
            alive = false;
        };
    }, []);

    useEffect(() => {
        loadSessions();

        const requestedSessionId = Number(requestedSessionParam);
        if (Number.isInteger(requestedSessionId) && requestedSessionId > 0) {
            loadMessagesForSession(requestedSessionId);
        } else {
            localStorage.removeItem('ss_session');
        }

        return () => {
            if (typingTimerRef.current) clearInterval(typingTimerRef.current);
        };
    }, [loadSessions, loadMessagesForSession, requestedSessionParam]);

    useEffect(() => {
        if (scrollRef.current && shouldAutoScrollRef.current) {
            scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
        }
    }, [messages]);

    useEffect(() => {
        const el = inputRef.current;
        if (!el) return;
        el.style.height = 'auto';
        el.style.height = `${Math.min(el.scrollHeight, 132)}px`;
        el.style.overflowY = el.scrollHeight > 132 ? 'auto' : 'hidden';
    }, [input]);

    const handleScroll = () => {
        const el = scrollRef.current;
        if (!el) return;
        const distanceFromBottom = el.scrollHeight - el.scrollTop - el.clientHeight;
        shouldAutoScrollRef.current = distanceFromBottom < 80;
    };

    const disableAutoScrollWhileReading = () => {
        if (isChatBusy) {
            shouldAutoScrollRef.current = false;
        }
    };

    const appendTypingReply = (reply, confirmationPrompts = []) => {
        const id = `a-${Date.now()}`;
        const safeReply = removeInlineDisclaimer(reply)
            || '답변을 생성하지 못했습니다. 다시 시도해 주세요.';
        const prompts = normalizeConfirmationPrompts(confirmationPrompts);
        let index = 0;

        setMessages((prev) => [...clearConfirmationPrompts(prev), { id, type: 'ai', text: '', time: now(), confirmationPrompts: [] }]);
        if (typingTimerRef.current) clearInterval(typingTimerRef.current);

        typingTimerRef.current = setInterval(() => {
            index += 2;
            setMessages((prev) => prev.map((message) => (
                message.id === id ? { ...message, text: safeReply.slice(0, index) } : message
            )));

            if (index >= safeReply.length) {
                clearInterval(typingTimerRef.current);
                typingTimerRef.current = null;
                if (prompts.length) {
                    setMessages((prev) => prev.map((message) => (
                        message.id === id ? { ...message, confirmationPrompts: prompts } : message
                    )));
                }
            }
        }, 16);
    };

    const shouldConfirmTopic = (content) => {
        const previousUserMessages = messages
            .filter((message) => message.type === 'user')
            .map((message) => message.text || '');

        return previousUserMessages.length > 0
            && (
                hasExplicitTopicShiftHint(content)
                || hasMeaningfulTopicShift(content, previousUserMessages)
            );
    };

    const sendOrAskTopic = (text) => {
        const content = (text || input).trim();
        if (!content || isChatBusy) return;

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
        if (!content || isSessionLoading || pendingKeysRef.current.has(targetKey)) return;
        const guestSend = isGuestMode || !hasToken();
        const targetSessionId = guestSend || options.forceNewSession ? null : sessionIdRef.current;
        const guestHistory = guestSend ? toGuestHistoryPayload(messages) : [];

        setInput('');
        setConfirmationDrafts({});
        shouldAutoScrollRef.current = true;
        if (options.forceNewSession) {
            messageLoadSequenceRef.current += 1;
            localStorage.removeItem('ss_session');
            activateConversation(null, targetKey);
            setIsSessionLoading(false);
            setShowReport(false);
        }
        setMessages((prev) => [
            ...(options.forceNewSession ? [] : clearConfirmationPrompts(prev)),
            { id: `u-${Date.now()}`, type: 'user', text: content, time: now() },
        ]);
        pendingKeysRef.current.add(targetKey);
        setPendingKeys((prev) => {
            const next = new Set(prev);
            next.add(targetKey);
            return next;
        });

        try {
            const data = await api.post(
                guestSend ? '/chat/guest-message' : '/chat/message',
                guestSend ? { content, history: guestHistory } : { sessionId: targetSessionId, content }
            );
            if (conversationKeyRef.current === targetKey) {
                if (!guestSend && targetSessionId === null) {
                    activateConversation(data.session_id);
                }
                setShowReport(!guestSend && Boolean(data.report_ready));
                appendTypingReply(data.reply, data.confirmation_prompts);
            }
            if (!guestSend) loadSessions();
        } catch (e) {
            if (e.message?.includes('로그인이 필요')) clearSession();
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
        if (!sessionId || isChatBusy) return;
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

    const handleConfirmationOption = (messageId, prompt, promptIndex, option) => {
        if (isChatBusy) return;
        const message = String(option?.message || '').trim();
        if (!message) return;

        const key = confirmationPromptKey(messageId, prompt, promptIndex);
        const direct = isDirectConfirmationOption(option);
        setConfirmationDrafts((prev) => {
            const previousDraft = prev[key];
            const selectedOptions = selectedConfirmationOptions(previousDraft);
            const optionKey = confirmationOptionKey({ ...option, message });
            const alreadySelected = selectedOptions.some((selected) => (
                confirmationOptionKey(selected) === optionKey
            ));
            const nextSelectedOptions = alreadySelected
                ? selectedOptions.filter((selected) => confirmationOptionKey(selected) !== optionKey)
                : [...selectedOptions, { label: option.label, message, direct }];

            return {
                ...prev,
                [key]: {
                    selectedOptions: nextSelectedOptions,
                    customText: previousDraft?.customText || '',
                },
            };
        });
    };

    const handleConfirmationCustomChange = (messageId, prompt, promptIndex, value) => {
        const key = confirmationPromptKey(messageId, prompt, promptIndex);
        setConfirmationDrafts((prev) => ({
            ...prev,
            [key]: {
                selectedOptions: selectedConfirmationOptions(prev[key]),
                customText: value,
            },
        }));
    };

    const confirmationAnswersForMessage = (message) => (message.confirmationPrompts || [])
        .map((prompt, promptIndex) => {
            const key = confirmationPromptKey(message.id, prompt, promptIndex);
            const draft = confirmationDrafts[key];
            if (!draft) return null;
            const selectedOptions = selectedConfirmationOptions(draft);
            const customText = String(draft.customText || '').trim();
            const selectedAnswers = selectedOptions
                .map((option) => {
                    const messageText = String(option.message || '').trim();
                    if (!messageText) return null;
                    if (option.direct) {
                        if (!customText) return null;
                        return messageText.endsWith(':') ? `${messageText} ${customText}` : `${messageText} ${customText}`;
                    }
                    return messageText;
                })
                .filter(Boolean);

            const hasDirectSelection = selectedOptions.some((option) => option.direct);
            if (customText && !hasDirectSelection) {
                selectedAnswers.push(`추가 설명: ${customText}`);
            }

            return selectedAnswers.length ? selectedAnswers.join('\n') : null;
        })
        .filter(Boolean);

    const handleSendConfirmation = (message) => {
        if (isChatBusy) return;
        const answers = confirmationAnswersForMessage(message);
        if (!answers.length) return;
        setMessages((prev) => prev.map((item) => (
            item.id === message.id ? { ...item, confirmationPrompts: [] } : item
        )));
        handleSend(answers.join('\n'));
    };

    const handleNewChat = () => {
        messageLoadSequenceRef.current += 1;
        localStorage.removeItem('ss_session');
        activateConversation(null, `draft:${Date.now()}`);
        setIsSessionLoading(false);
        setConfirmationDrafts({});
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

    const handleInputKeyDown = (e) => {
        if (e.key !== 'Enter') return;
        if (e.shiftKey) return;
        e.preventDefault();
        sendOrAskTopic();
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
                            <div className="ss-nav-item" onClick={() => (isGuest ? requireLoginForSavedFeature() : navigate('/result'))}>
                                <span className="ss-emoji-icon">R</span>
                                <span className="ss-nav-text">분석 결과</span>
                            </div>
                            <div className="ss-nav-item" onClick={() => (isGuest ? requireLoginForSavedFeature() : navigate('/mypage'))}>
                                <span className="ss-emoji-icon">M</span>
                                <span className="ss-nav-text">마이페이지</span>
                            </div>
                            <div className="ss-nav-item active">
                                <span className="ss-emoji-icon">C</span>
                                <span className="ss-nav-text">상담</span>
                            </div>
                        </nav>

                        {isGuest ? (
                            <section className="guest-history-notice">
                                <strong>임시 상담 중</strong>
                                <span>로그인하지 않아 상담 기록은 저장되지 않습니다.</span>
                                <button type="button" onClick={() => navigate('/login')}>로그인하고 저장하기</button>
                            </section>
                        ) : (
                            <SessionHistory
                                sessions={sessions}
                                activeSessionId={sessionId}
                                onSelect={loadMessagesForSession}
                                loading={isSessionsLoading}
                            />
                        )}

                        <div className="ss-logout-section">
                            <div className="ss-divider"></div>
                            <button className="ss-side-action-btn" onClick={handleNewChat}>새 상담</button>
                            {isGuest ? (
                                <button className="ss-logout-btn" onClick={() => navigate('/login')}>로그인</button>
                            ) : (
                                <button className="ss-logout-btn" onClick={() => setShowConfirm(true)}>로그아웃</button>
                            )}
                        </div>
                    </div>
                </div>
            </aside>

            <main className="ss-chat-main">
                <header className="ss-chat-header">
                    <div className="ss-chat-logo">S-<span className="logo-accent">Shield</span></div>
                    <nav className="ss-mobile-nav" aria-label="모바일 메뉴">
                        <button className="active">상담</button>
                        <button onClick={() => (isGuest ? requireLoginForSavedFeature() : navigate('/result'))}>결과</button>
                        <button onClick={() => (isGuest ? requireLoginForSavedFeature() : navigate('/mypage'))}>마이</button>
                        <button onClick={() => (isGuest ? requireLoginForSavedFeature() : navigate('/mypage#session-history'))}>기록</button>
                        <button onClick={handleNewChat}>새 상담</button>
                    </nav>
                    <div className="ss-header-line"></div>
                </header>

                <div
                    className={`ss-chat-content ${isSessionLoading ? 'loading' : ''}`}
                    ref={scrollRef}
                    onScroll={handleScroll}
                    onWheel={disableAutoScrollWhileReading}
                    onTouchStart={disableAutoScrollWhileReading}
                    onPointerDown={disableAutoScrollWhileReading}
                >
                    {isSessionLoading ? (
                        <div className="ss-chat-loading" role="status" aria-live="polite">
                            <div className="typing-indicator" aria-label="상담 기록 불러오는 중">
                                <span className="typing-dot"></span>
                                <span className="typing-dot"></span>
                                <span className="typing-dot"></span>
                            </div>
                            <p>상담 기록을 불러오는 중...</p>
                        </div>
                    ) : (
                        <>
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
                                        {msg.type === 'ai' && msg.confirmationPrompts?.length > 0 && (
                                            <div className="confirmation-prompts">
                                                {msg.confirmationPrompts.map((prompt, promptIndex) => {
                                                    const promptKey = confirmationPromptKey(msg.id, prompt, promptIndex);
                                                    const draft = confirmationDrafts[promptKey];
                                                    const selectedOptionKeys = new Set(
                                                        selectedConfirmationOptions(draft).map(confirmationOptionKey)
                                                    );
                                                    return (
                                                    <div className="confirmation-prompt" key={promptKey}>
                                                        <span className="confirmation-kicker">다음 확인</span>
                                                        <p>{prompt.question}</p>
                                                        <span className="confirmation-multi-hint">
                                                            {prompt.instruction || '여러 개 선택 가능 · 필요하면 직접 입력도 함께 작성'}
                                                        </span>
                                                        <div className="confirmation-options">
                                                            {prompt.options.map((option) => {
                                                                const optionKey = confirmationOptionKey(option);
                                                                const selected = selectedOptionKeys.has(optionKey);
                                                                return (
                                                                    <button
                                                                        key={`${prompt.id}-${option.label}`}
                                                                        className={selected ? 'selected' : ''}
                                                                        type="button"
                                                                        aria-pressed={selected}
                                                                        onClick={() => handleConfirmationOption(msg.id, prompt, promptIndex, option)}
                                                                        disabled={isChatBusy}
                                                                    >
                                                                        {option.label}
                                                                    </button>
                                                                );
                                                            })}
                                                        </div>
                                                        <input
                                                            className="confirmation-custom-input"
                                                            type="text"
                                                            value={draft?.customText || ''}
                                                            placeholder="선택 없이 직접 답변하거나, 선택 후 설명을 덧붙일 수 있습니다"
                                                            onChange={(e) => handleConfirmationCustomChange(msg.id, prompt, promptIndex, e.target.value)}
                                                            onKeyDown={(e) => {
                                                                if (e.key === 'Enter' && confirmationAnswersForMessage(msg).length) {
                                                                    handleSendConfirmation(msg);
                                                                }
                                                            }}
                                                            disabled={isChatBusy}
                                                        />
                                                    </div>
                                                    );
                                                })}
                                                <div className="confirmation-submit-row">
                                                    <button
                                                        type="button"
                                                        className="confirmation-submit-btn"
                                                        onClick={() => handleSendConfirmation(msg)}
                                                        disabled={isChatBusy || !confirmationAnswersForMessage(msg).length}
                                                    >
                                                        확인 답변 보내기
                                                    </button>
                                                    <span>선택한 답변을 모아서 상담에 추가합니다.</span>
                                                </div>
                                            </div>
                                        )}
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
                        </>
                    )}
                </div>

                <footer className="ss-chat-footer">
                    <div className="example-chips">
                        {EXAMPLE_QUESTIONS.map((q) => (
                            <button key={q} className="example-chip" onClick={() => sendOrAskTopic(q)} disabled={isChatBusy}>
                                {q}
                            </button>
                        ))}
                    </div>

                    {showReport && (
                        <div className="chat-cta">
                            <p>핵심 정보가 확인되었습니다. 분석 리포트를 생성해 확인해 보세요.</p>
                            <button className="chat-cta-btn" onClick={handleGenerateReport} disabled={generatingReport || isChatBusy}>
                                {generatingReport ? '생성 중...' : '리포트 보기'}
                            </button>
                        </div>
                    )}

                    <div className="ss-input-box">
                        <textarea
                            ref={inputRef}
                            placeholder="상황을 입력해 주세요"
                            value={input}
                            onChange={(e) => setInput(e.target.value)}
                            onKeyDown={handleInputKeyDown}
                            disabled={isChatBusy}
                            rows={1}
                        />
                        <button className="ss-send-btn" onClick={() => sendOrAskTopic()} disabled={isChatBusy}>
                            보내기
                        </button>
                    </div>
                    {isGuest && (
                        <p className="ss-guest-notice">
                            임시 상담입니다. 로그인하지 않으면 상담 기록과 리포트는 저장되지 않습니다.
                        </p>
                    )}
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
