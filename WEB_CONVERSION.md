# Web conversion

Tracker for moving BaerenEd from the Android app to browser pages on the `web` branch.

`V3` stays the Android app. New pages go in `web/` so they do not replace the HTML the tablets already load from `app/src/main/assets/html/` until a piece is ready.

## Decisions

- GitHub Pages hosts the pages. The Supabase anon key is typed into a parent config and stored in the browser (`localStorage`), same pattern as `reports/index.html`, behind a PIN. The key is not committed into the page source.
- The web task list comes from `web_games` and `web_assignments`. The tablet still reads the GitHub JSON configs and the existing reset functions.
- Speech for spelling-style games is one browser utterance at a slightly slower rate. No second slow reading of the word, and no lock-until-speech-ends.
- YouTube, Chrome tasks, Boukili, Google Read Along, and Kung Fu videos are not ported.
- On-device OCR scoring is not ported. Handwriting is drawn in the page and uploaded for Grok.
- Opening BaerenLock from a page is not possible. Saving reward minutes in the database can still be a button.

## Already HTML

These run inside the Android WebView today. The shell opens them in `web/play.html`, which provides browser speech, JSON loading, and completion in place of `Android.*`. A box stays open until that game has been tried in the browser.

- [ ] Spelling jumble (English and French)
- [ ] Spelling drag (English and French)
- [ ] Spelling photo (English and French)
- [ ] Word Garden
- [ ] Word Fishing
- [ ] Word Factory
- [ ] Snake Catch
- [ ] Spelling Race
- [ ] UFLI Word Chain
- [ ] UFLI Irregular Words
- [ ] Math Hole
- [ ] Number Line Math
- [ ] Ten Frame Math
- [ ] Time Telling
- [ ] Times Tables
- [ ] Pie Fractions
- [ ] Math Strategies Coach
- [ ] Multi-digit add/subtract
- [ ] Multi-digit multiplication
- [ ] Long Division
- [ ] French Vowels
- [ ] Story Sequence
- [ ] Diagram Labeler
- [ ] Handwriting Paper Snapshot
- [ ] Photo chores (`chorePhoto.html`)
- [ ] Audio chores (`choreAudio.html`)
- [ ] Video chores (`choreVideo.html`)
- [ ] Pack school lunch
- [ ] Parent reports (already use `localStorage`)

## Still to build

- [x] **Shell.** `web/index.html` — PIN config, profile (AM/BM/TE), today's task list from the config JSON, open a game. Existing HTML games open in `web/play.html`, which supplies browser speech and completion. Writing completion still needs the Supabase key saved in that browser.
- [x] **Quiz page.** `web/quiz.html` for the shared `GameActivity` JSON (prompt, optional picture, answer tiles). Covers JK Math, Math Strategies Practice, the grade-3 set, Money, Conjugation, Translation, Duological, and French Stories. French story-read is still separate.
- [ ] **Trainer map.** Gym buttons and completion from the task RPCs. No YouTube, Chrome, or other-app launches.
- [ ] **Battle hub.** Berries, Pokédex, battle, and daily spin via the existing RPCs. Reward-time button writes minutes only. It does not open BaerenLock.
- [x] **Spelling OCR.** `web/spell.html` draws the word, speaks one slower prompt, and uploads every drawing as incorrect. The last word calls `af_enqueue_spelling_ocr_review` so the Grok review scores the set.
- [ ] **Printing.** Canvas and upload, still separate from Spelling OCR.
- [ ] **Tappable books.** Passage, tap the word, comprehension questions. No highlight locked to the voice.

## Left off

Not in this conversion unless asked later:

- Sight Words, Sentence Builder, and UFLI Words
- YouTube and playlists
- Google Classroom and Je Lis
- Boukili and Google Read Along
- Kung Fu videos
