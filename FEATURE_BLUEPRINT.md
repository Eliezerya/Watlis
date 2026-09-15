# Watlis: next-feature blueprint

Prepared September 15, 2026.

Progress history + Undo (feature 1) is now implemented. The remaining features are proposals. The examples below are illustrations only; they must not be added to your collection automatically.

## The direction

Make Watlis better at answering three questions:

1. What should I continue reading?
2. Where did I stop, and what happened before that?
3. Is my collection safely saved?

Keep Java, the Watlis name, offline tracking, genre-colored accents, and lightweight image loading. Add features gradually without restructuring the whole app.

## What you already have

The current code includes media tracking, fractional chapter/episode progress, ratings, favorites, search, filters, sorting, genres, editable media types, statistics, story reminders, personal notes, characters with photos, cover positioning/zoom, full-screen image previews, and manual JSON backup/import.

The ideas below extend these features instead of recreating them.

## At a glance

Effort is relative: Small = a focused addition; Medium = new storage or several connected screens; Large = more complex data recovery and testing. These are planning estimates, not delivery promises.

| Order | Feature | What it helps you do | Effort |
| --- | --- | --- | --- |
| 1 | Progress history + Undo — implemented | Recover from accidental taps and remember reading sessions | Medium |
| 2 | Reading links | Reopen the site or app where you read a title | Small |
| 3 | Safer backup and restore | See what a backup contains before replacing your collection | Medium |
| 4 | Continue reading shelf | Find a few active titles without searching the whole list | Small |
| 5 | Personal collections | Group titles by your own purpose, separately from genres | Medium |
| 6 | Chapter-linked, spoiler-aware notes | Keep several reminders tied to particular chapters | Medium |
| 7 | Optional reading reminders | Get a gentle reminder for a title you choose | Medium |
| 8 | Reading comfort settings | Choose clearer contrast and comfortable spacing | Medium |

My suggested next feature is **Reading links**, now that Progress history + Undo is implemented. Neither needs accounts, a server, or background content fetching.

## 1. Progress history + Undo

**Status: Implemented.** Both List and Detail offer Undo after a progress change. Detail's History action opens a paged log; the latest eligible change can also be undone there after reopening the app. Plus taps are reading updates; decreases, manual entry, and progress edits in the media form are corrections. Initial progress is a baseline, not a fabricated reading session. History is included in version-5 backups.

**What you see:** A small History action beside progress in Detail. After changing progress, a brief message offers Undo.

**Example:** You accidentally change chapter 24 to 25. Tap Undo to return to 24. History distinguishes a reading update from a correction.

**Screen flow:** Detail → History → recent progress changes, with dates and previous/new values.

**First version:** Record future progress changes from both List and Detail. Load older entries only when you scroll. Do not invent past reading history from the current chapter number.

**Rules:**

- Keep decimal progress, such as 24.5.
- Undo applies only to the latest eligible change; it must not overwrite a newer update.
- Save progress and its history together, so one cannot succeed without the other.
- Editing a title, cover, or character must not count as a reading session.

**Ready when:** Rapid taps, Undo, manual corrections, app reopening, and backup restoration all produce the correct progress and history.

## 2. Reading links

**What you see:** A Reading links section in Detail, with a label and an Open action. Example label: “Official reader.”

**Screen flow:** Detail → Add reading link → enter a label and URL → Save → Open.

**First version:** Allow several links per title, with one selected as the main link. Support edit and removal.

**Rules:**

- Show the destination hostname before opening it.
- Open links outside Watlis. Do not add a built-in reader, scraping, or automatic downloads.
- Never send private notes or your collection to the destination.
- Invalid links show an inline error without discarding the form.

**Ready when:** Links survive editing, reopening, and backup restoration; malformed links do not crash the app.

## 3. Safer backup and restore

**What you see:** A Backup screen showing the last successful export and what your backup includes.

**Screen flow:** Choose backup → review its date and contents → confirm replacement → restore → show result.

**First version:** Improve the existing manual export/import. Preview title, character, genre, and image counts before replacing anything. Clearly explain that saved thumbnails are included, not full-resolution originals.

**Rules:**

- Record “last exported” only after the file is successfully written.
- Validate the complete backup before changing the current collection.
- Offer an export of the current collection before replacement.
- A failed restore must leave the current collection usable and unchanged.
- An in-app copy alone is not protection against uninstalling or clearing app data. Explain where the user's export is stored.

**Later:** Optional scheduled backups to a user-selected location, with retention controls. Do not include this in the first version until access loss, storage limits, and failure reporting are designed.

**Ready when:** Valid, old-format, damaged, and interrupted backups are tested, including character photos and missing originals.

## 4. Continue reading shelf

**What you see:** A compact, collapsible section above the normal list, showing up to three active titles and their current progress.

**Screen flow:** Home → choose a title → its existing Detail page and “Before you continue” section.

**First version:** Use reading/watching status and actual progress-update dates. Metadata edits must not move a title to the front.

**Rules:** Do not duplicate the full list, load original photos, or block Home while calculating this section. Hide it when there are no active titles. Give the user a switch to hide it entirely.

**Ready when:** Empty collections, recently edited covers, long titles, and large text do not create confusing ordering or clipped cards.

## 5. Personal collections

**What you see:** A Collections entry in the drawer. Examples you could create yourself: “Weekend reading” or “On hold.”

**Screen flow:** Collections → New collection → name it → select titles → open the collection's list.

**First version:** Create, rename, and delete collections. A title can belong to several collections.

**Rules:** Collections describe your organization; genres describe the media. Deleting a collection must never delete its titles, notes, or photos. Avoid introducing another permanent navigation bar.

**Ready when:** Membership works with search/filtering and backup restoration, and collection deletion preserves all media.

## 6. Chapter-linked, spoiler-aware notes

**What you see:** A Chapter notes section in Detail. Each note has a chapter/episode number, an optional heading, and its text.

**Screen flow:** Detail → Add chapter note → choose progress number → write → Save or Keep when leaving.

**Example:** A note attached to chapter 42 can stay collapsed while your current progress is 30, showing “Future chapter note — tap to reveal.”

**First version:** Plain text only. Keep the existing main-character, story reminder, last-story-point, and important-notes fields as the quick summary.

**Rules:**

- Spoiler protection is a user-controlled display setting, not a guarantee about the content.
- Do not show hidden note text in previews or search snippets unless revealed.
- Changing reading progress must not delete notes.
- Moving existing reminders into chapter notes requires an explicit user action.

**Ready when:** Decimal chapter numbers, hidden/revealed notes, Keep/Discard, editing, and backup restoration work without losing existing reminders.

## 7. Optional reading reminders

**What you see:** A Remind me action in Detail, with a chosen day/time and an Off switch.

**Screen flow:** Detail → Remind me → select schedule → enable → reminder opens that title's Detail page.

**First version:** User-created reminders only. This is not an automatic new-chapter detector.

**Rules:** Ask for notification permission only when the user enables reminders. Respect denial and disabled notifications. Pause reminders for deleted or completed titles. Avoid background checks of reading websites. Verify Android scheduling and permission requirements during implementation; do not promise exact-to-the-minute delivery.

**Ready when:** Permission denial, changed schedules, time-zone changes, app restarts, and title deletion do not cause crashes or unwanted notifications.

## 8. Reading comfort settings

**What you see:** An Appearance section offering the existing black background or a softer charcoal background, plus Comfortable and Compact list spacing.

**Screen flow:** Appearance → select option → preview → Apply.

**First version:** Keep the same Watlis layout and genre accents. Respect system text sizing. Dialogs and fields remain visually separated in either theme.

**Rules:** Never use color alone to indicate selection or errors. Keep controls comfortably tappable. Do not add animated backgrounds or reload the collection just to change appearance.

**Ready when:** Long titles, several genres, empty states, keyboard-open forms, and enlarged text remain readable without clipping. Settings survive reopening the app.

## Suggested release order

| Release | Scope | Stop and check before continuing |
| --- | --- | --- |
| A: Everyday use | Progress history + Undo; Reading links | Accidental changes can be recovered; links open safely |
| B: Trust and comfort | Safer backup/restore; Appearance settings | Recovery preserves data; screens remain readable |
| C: Organization | Continue reading shelf; Personal collections | Home stays quick and uncluttered |
| D: Optional extras | Chapter notes; Reading reminders | No accidental spoilers, lost notes, or unwanted notifications |

Each feature can ship independently. Start with one and use it before adding the next.

## Ideas to leave for later

- Character relationships and aliases, if remembering a large cast becomes difficult.
- A reading-calendar view, once real progress history exists to support it.
- Recoverable trash for deleted media. Useful, but it needs a clear retention policy covering characters, notes, images, and backups.
- Cloud sync, automatic metadata lookup, and release detection. These add privacy, connectivity, conflict-resolution, and maintenance work; they are not needed for the first releases.

## Shared rules for whoever builds this

- Keep Java, the Watlis package/name, existing behavior, and the current collection.
- Make small, feature-specific changes. No broad refactor is required by this blueprint.
- Use additive database migrations; never reset the user's database to make a feature work.
- Extend backups when new data is introduced, and keep older backups readable.
- Use saved thumbnails in lists. Decode higher-resolution images only when the user opens a preview.
- Run storage and image work away from the interface thread. Load history and long lists in bounded batches.
- Keep optional features off or hidden until relevant. Do not add demo entries to the user's collection.
- Verify each release with a clean build, data-migration tests, UI tests, and visual checks at normal and enlarged text sizes.
- Compare startup time and memory against the same device and collection before the change. Include a separate large-library test fixture. Do not claim “no delay” or “no bugs” without evidence.

**Next step:** Choose one of the remaining proposed features to build. Feature 1 is complete; the others are not implemented yet.
