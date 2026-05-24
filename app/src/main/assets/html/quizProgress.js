/**
 * Shared quiz progress: wrong answers count and advance; game ends after N correct (first try per question).
 * Matches native GameEngine behavior. Read totalQuestions from URL when present.
 */
(function (global) {
    function parseRequiredCorrect(defaultRequired, override) {
        if (override != null && Number.isInteger(override) && override > 0) {
            return override;
        }
        const params = new URLSearchParams(global.location.search);
        const raw = params.get('totalQuestions');
        const parsed = Number(raw);
        if (Number.isInteger(parsed) && parsed > 0) {
            return parsed;
        }
        return defaultRequired;
    }

    function create(options) {
        const opts = options || {};
        let requiredCorrect = parseRequiredCorrect(opts.defaultRequired ?? 10, opts.requiredCorrect);
        let correctCount = 0;
        let incorrectCount = 0;
        let bankIndex = 0;

        return {
            init(newRequired) {
                if (newRequired != null) {
                    requiredCorrect = parseRequiredCorrect(requiredCorrect, newRequired);
                }
                correctCount = 0;
                incorrectCount = 0;
                bankIndex = 0;
            },
            setRequiredCorrect(n) {
                requiredCorrect = parseRequiredCorrect(requiredCorrect, n);
            },
            getRequiredCorrect() {
                return requiredCorrect;
            },
            getCorrectCount() {
                return correctCount;
            },
            getIncorrectCount() {
                return incorrectCount;
            },
            getQuestionsAnswered() {
                return correctCount + incorrectCount;
            },
            getBankIndex() {
                return bankIndex;
            },
            wrapIndex(length) {
                return length > 0 ? bankIndex % length : 0;
            },
            recordAnswer(wasCorrect) {
                if (wasCorrect) {
                    correctCount += 1;
                } else {
                    incorrectCount += 1;
                }
                bankIndex += 1;
                return {
                    wasCorrect: !!wasCorrect,
                    correctCount,
                    incorrectCount,
                    questionsAnswered: correctCount + incorrectCount,
                    isComplete: correctCount >= requiredCorrect
                };
            },
            isComplete() {
                return correctCount >= requiredCorrect;
            },
            progressLabel() {
                const asked = correctCount + incorrectCount;
                return `Correct ${correctCount} / ${requiredCorrect} • Asked ${asked}`;
            },
            notifyAndroidComplete() {
                if (global.Android && typeof global.Android.gameCompleted === 'function') {
                    global.Android.gameCompleted(correctCount, incorrectCount);
                }
                if (global.parent && global.parent !== global) {
                    global.parent.postMessage(
                        { type: 'gameCompleted', correct: correctCount, incorrect: incorrectCount },
                        '*'
                    );
                }
            }
        };
    }

    global.QuizProgress = { create, parseRequiredCorrect };
})(typeof window !== 'undefined' ? window : globalThis);
