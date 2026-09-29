# Web conversion

Tracker for moving BaerenEd from the Android app to browser pages on the `web` branch.

`V3` stays the Android app. New pages go in `web/` so they do not replace the HTML the tablets already load from `app/src/main/assets/html/` until a piece is ready.

## Decisions

- GitHub Pages hosts the pages. The Supabase anon key is typed into a parent config and stored in the browser (`localStorage`), same pattern as `reports/index.html`, behind a PIN. The key is not committed into the page source.
- Speech for spelling-style games is one browser utterance at a slightly slower rate. No second slow reading of the word, and no lock-until-speech-ends.
- YouTube, Chrome tasks, Boukili, Google Read Along, and Kung Fu videos are not ported.
- On-device OCR scoring is not ported. Handwriting is drawn in the page and uploaded for Grok.
- Opening BaerenLock from a page is not possible. Saving reward minutes in the database can still be a button.

## Already HTML

These run inside the Android WebView today. On `web` they need to stop calling `Android.*` and use the browser key, browser speech, and the existing RPCs (`af_update_task_completion`, game index, and so on).

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

- [ ] **Shell.** PIN config, profile (AM/BM/TE), today's task list from the existing config, open a game, write completion.
- [ ] **Quiz page** for the shared `GameActivity` JSON (prompt, optional picture, answer tiles). Covers JK Math, Math Strategies Practice, the grade-3 set, Money, Conjugation, Translation, Duological, and French Stories. French story-read is the same idea plus page pictures and a Next button.
- [ ] **Trainer map.** Gym buttons and completion from the task RPCs. No YouTube, Chrome, or other-app launches.
- [ ] **Battle hub.** Berries, Pokédex, battle, and daily spin via the existing RPCs. Reward-time button writes minutes only. It does not open BaerenLock.
- [ ] **Printing and Spelling OCR.** Canvas, one spoken prompt at the slower rate (French and English), upload the drawing. No ML Kit score.
- [ ] **Tappable books.** Passage, tap the word, comprehension questions. No highlight locked to the voice.

## Left off

Not in this conversion unless asked later:

- Sight Words, Sentence Builder, and UFLI Words
- YouTube and playlists
- Google Classroom and Je Lis
- Boukili and Google Read Along
- Kung Fu videos
