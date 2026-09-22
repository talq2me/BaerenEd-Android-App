---
name: ufli-weekly-home-practice
description: >-
  Loads this week's UFLI Home Practice sheet into AM Word Chain, Irregular
  Words, and sentence-read (chore audio) tasks. Use when the user pastes or
  attaches a UFLI Foundations Home Practice image/PDF, or asks to update
  UFLI chain, irregular words, or UFLI sentences for the week.
---

# Weekly UFLI Home Practice

When the user gives a UFLI Home Practice image (or equivalent text), extract the week's content and point the **AM** games at it. Content is live after **GitHub Pages** publish. Do **not** require an Android rebuild. Do **not** commit or push unless asked.

Also follow `.cursor/rules/ufli-word-chain-authoring.mdc` for chain/irregular JSON.

## What to extract from the sheet

| Sheet section | Game |
|---|---|
| Word Work Chains (numbered `just → rust → …`) | UFLI Word Chain |
| Review Irregular Words (`look*`, `are`, …) | UFLI Irregular Words |
| Sentences (1. … 2. …) | Two (or more) chore-audio tasks |
| New Concept and Sample Words | Extra **vocab** rounds on the chain (not a fourth game) |

Ignore parent directions, `[spelling]` / `[reading]` tags, logos, and page chrome.

Strip `*` from irregular words (`look*` → `look`). Keep the user's exact letter sequences otherwise.

## Concept slug

From “New Concept …” make a lowercase hyphen slug, e.g. `short vowel advanced review 1` → `short-vowel-advanced-review-1`.

Files (create or overwrite):

- `app/src/main/assets/ufliWordChains/ufli-chain-<slug>-g1.json`
- `app/src/main/assets/ufliIrregularWords/ufli-irr-<slug>-g1.json`

Then run:

```bash
python scripts/regenerate_ufli_index.py
```

## 1. Word Chain

v2 group JSON (`id`, `concept`, `lang: "eng"`, `groups`).

- Each numbered chain → `chain-1`, `chain-2`, …
- First word is `group.seed` (`Make the word X.` / `Write the word X.`). One grapheme per `correctChoices` tile; 2–4 `extraChoices`. **No slider for the seed.**
- Later arrows → `{ "from", "to", "prompt": "Say <from>. Change … Build <to>." }`
- `from` must be the previous `to` (or seed). One insert/replace/delete region only. If a jump is too big, insert an intermediate word or use a `type: "seed"` spell step.
- Sample words that do not chain cleanly → one `vocab` group with `continuesFrom` = last chain word, each sample as `{ "word", "prompt": "Build <word>.", "correctChoices", "extraChoices", "type": "seed" }`.

Do **not** put irregular words or Home Practice sentences into the chain file.

## 2. Irregular Words

JSON **array** (not grouped). One object per heart word:

```json
{
  "prompt": { "text": "Build the irregular word: look.", "lang": "eng", "displayText": true },
  "question": { "text": "look", "lang": "eng", "displayText": true },
  "correctChoices": [{ "text": "l" }, { "text": "oo" }, { "text": "k" }],
  "extraChoices": [{ "text": "u" }, { "text": "ck" }]
}
```

Keep a parenthetical sample sentence in `prompt.text` only if the sheet provides one. Prefer UFLI graphemes (`oo`, `wh`, `ou`) when obvious. Use `[]` if the sheet has no irregulars.

## 3. Sentences = existing chore audio (no new HTML)

Do **not** add `sentenceRead.html` or change Kotlin. Use `choreAudio.html` already in the APK.

Required AM tasks (required **and** keep launch ids stable):

- `choreId=ufli_sentence_1` launch `choreUfliSentence1`
- `choreId=ufli_sentence_2` launch `choreUfliSentence2`

If the sheet has more than two sentences, add `ufli_sentence_3` / `choreUfliSentence3`, etc. If fewer, remove extra tasks so the kid is not asked to read last week's leftover sentence.

Per sentence, URL-encode `title` = the sentence text and `description=Read%20this%20sentence%20out%20loud.`

```
https://talq2me.github.io/BaerenEd-Android-App/app/src/main/assets/html/choreAudio.html?choreId=ufli_sentence_1&title=<ENCODED_SENTENCE>&description=Read%20this%20sentence%20out%20loud.&rewardCash=0&maxSeconds=60
```

Task `title` in config = the same sentence (plain text). `stars`: 3. `webGame`: true. `rewardCash=0`.

Edit **both**:

- `app/src/main/assets/config/AM_config.json`
- `app/src/main/assets/config/schedule_master_AM.json` (include `scheduleKey`)

Do not change BM/TE unless the user says those profiles should get the same week.

## 4. Pin chain + irregular URLs (do not rely on rotation)

Replace AM required **and** practice `UfliSpellingChain.html` query strings:

- Chain: `...?mode=chain&file=ufli-chain-<slug>-g1.json`
- Irregular: `...?mode=spell&file=ufli-irr-<slug>-g1.json`

Leave `poolKey` / other games alone. Do not retarget Word Garden, Jumble, or school spelling lists unless asked.

## 5. Finish

- Do not invent a new native “UFLI Words” file unless asked (`ufliLessonData` is a separate bank).
- Summarize what was loaded (chains, irregulars, sentences).
- Remind: tablets see it after GitHub Pages publish; sentence recording uses the current APK.

## Example (short-vowel advanced review 1)

Chains: `just→rust→rest→west`, `mist→fist→fast→past`  
Irregulars: look, book, are, was, you, what, have  
Sentences: `He went to get help.` / `What is the best spot?`  
Slug: `short-vowel-advanced-review-1`
