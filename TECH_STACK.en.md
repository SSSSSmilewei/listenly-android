# Listenly: Old Tech Stack vs. New Tech Stack

> A guide anyone can read, including people who have never written code.
> It explains what the app is built with today (version 2.0.1), what the rewrite will be built with (version 3.0.0), how the two differ, and why the change is worth it.

| | Old (current) | New (planned rewrite) |
|---|---|---|
| App version | **2.0.1** | **3.0.0** |
| Android `versionCode` | **16** | **300** (any number above 16 works; it must be higher so phones accept it as an update) |
| Package name | `cn.listenly.app` | `cn.listenly.app` (unchanged, so it installs **over** the old app and keeps your data) |
| Status | Released | Planned. This document is the blueprint. |

---

## 1. First, what is a "tech stack"?

An app is built from layers of tools stacked on top of each other, a bit like a house:

- **The foundation**: what the app runs on (Android, a phone's built-in browser engine, and so on).
- **The walls and plumbing**: the code that plays audio, saves your progress and reads your study files.
- **The paint and furniture**: what you see and touch (buttons, colors, animations).
- **The utility company** (optional): a server somewhere on the internet that the app can talk to.

The "tech stack" is the list of tools used for each layer. The tools you pick decide how fast the app feels, how much battery it uses, which features are possible, and how hard it is to keep improving.

---

## 2. The old stack (version 2.0.1), in plain words

### The big idea: a website wrapped inside an app

Listenly 2.0.1 is really **a small website that lives inside your phone**. The Android app is a thin frame whose only real job is to show that website. Think of a picture frame (the Android part) holding a printed web page (the actual app).

| Layer | Tool used | What it means in plain words |
|---|---|---|
| Foundation | **Android WebView** | A hidden, stripped-down Chrome browser built into Android. The whole app runs inside it. |
| What you see | **HTML + CSS + JavaScript**, no framework | The same languages used to build websites. Every screen is drawn as a web page. |
| Screen updates | **Redraw the whole page** | When anything changes (you tap a button, the theme switches), the app rebuilds the entire page from scratch. |
| Saving your data | **localStorage** (browser storage) | Your answers, bookmarks, progress and statistics are saved as **one big text blob** in the browser's storage. |
| Native frame | **Java**, about 1,500 lines | Opens the browser, feeds it the audio files, copies imported folders, and handles the back button and screen notch. |
| Audio playback | **The browser's `<audio>` player** | Audio plays through the browser, with a custom Java "pipe" feeding it the file piece by piece. |
| Making old phones work | **Babel + esbuild + core-js** | Tools that translate modern code into an older dialect so 2017-era browsers can still run it. |
| Fancy visuals | Custom "glass" and "moving lens" effects | Hand-written blur and refraction effects, plus a trick that takes a **screenshot** of the screen to fade between day and night themes smoothly. |
| Server | **None** | Fully offline. The app doesn't even ask for internet permission. |
| Testing | Node tests, Playwright (an automated browser), Android emulators on GitHub | Robots that click through the app to check nothing is broken. |

### What the old stack did well

- **Fully offline and private.** No account, no internet, nothing leaves your phone.
- **Easy to preview.** Because it's a website, you can open it in a desktop browser while developing.
- **Well tested.** It has automated checks on Android 8, 11 and 16.

### Where the old stack struggles

The project's own documentation records these problems. Each one comes from running a media app inside a browser:

1. **Audio needed a workaround, and it broke once.** The browser can't read app files directly, so a custom Java pipe feeds audio to it. In version 1.0.3 that pipe skipped data, and audio failed to play after reopening an article (fixed in 1.0.4). A native audio player wouldn't need the pipe at all.
2. **Smoothness is fragile.** Redrawing the whole page on every change is expensive. The day/night animation needed a screenshot trick to hide the cost, and 12 rounds of performance testing found no clear overall speedup. **Stable 120 fps has never been confirmed on a real phone.**
3. **No background playback.** Audio is tied to the screen. There's no lock-screen player and no notification controls, and Bluetooth headphone buttons aren't wired up.
4. **Your data lives in one fragile blob.** Every answer and statistic is in a single text entry in browser storage. That's hard to search, hard to back up, and impossible to sync between devices.
5. **Old-phone translation tax.** All code must be translated down to a 2017 browser dialect. That adds build complexity and limits which modern features can be used.
6. **One device only.** Progress on your phone can't follow you to a tablet or a new phone.

---

## 3. The new stack (version 3.0.0), in plain words

### The big idea: a real native app, plus an optional cloud

Version 3.0.0 drops the "website in a frame" approach. **The app is built directly for Android with Android's own modern tools.** It follows the **MIUIX / HyperOS design language** (the clean, rounded, softly blurred look of Xiaomi's system apps), with **no Google Material components**.

A small **optional sync server** is added, so people who want it can sync progress across devices. Anyone who doesn't sign in keeps a fully offline app.

#### Design inspiration (design language only, not copied features)

| Reference project | What we take from it |
|---|---|
| [miuix](https://github.com/compose-miuix-ui/miuix) | **The actual UI toolkit we build with.** HyperOS-style components, smooth "squircle" corners, real-time blur, MIUI-style navigation and settings screens. |
| [flutter_miuix](https://github.com/ChuxinNeko/flutter_miuix) | A reference for how the same design language looks and behaves. We use the original Compose version, not the Flutter port. |
| [SPlayer-Next](https://github.com/SPlayer-Dev/SPlayer-Next) | How a serious player is organized: a dedicated audio engine, system media controls, **lyric-style synced text** (our transcript sentences), colors that adapt to the theme, and separating fast "playhead" updates from slow "screen" updates for smoothness. |
| [MusicBot-Go](https://github.com/liuran001/MusicBot-Go) | How the **server** is organized: written in Go, with clean layers (config, database, services, handlers), SQLite storage, and Docker deployment. |

### The new stack, layer by layer

| Layer | Tool used | What it means in plain words |
|---|---|---|
| Language | **Kotlin** 2.4 | Google's officially recommended language for Android. Modern, safe, and catches many mistakes before the app ever runs. |
| What you see | **Compose Multiplatform** 1.12 + **miuix** | Screens are described as reusable building blocks, and only the parts that change are redrawn, not the whole page. miuix supplies the HyperOS look: buttons, cards, switches, sliders, pop-ups, blurred bars and squircle corners. |
| Design system | **MiuixTheme** | One central place for colors, fonts and shapes. Day/night mode and system-wallpaper colors (Monet) switch instantly, with no screenshot trick needed. |
| Navigation | **miuix-nav** | MIUI-style page transitions and the predictive back gesture (you see the previous page as you swipe back). |
| Audio playback | **Media3 / ExoPlayer** + **MediaSession** | Google's professional audio/video engine (used by YouTube-class apps). It reads files directly, seeks precisely, and changes speed without changing pitch. |
| Background audio | **MediaSessionService** | Audio keeps playing with the screen off, shows on the **lock screen** and in the **notification shade**, and responds to **Bluetooth headphone buttons** and car systems. |
| Saving your data | **Room** (a real database) + **DataStore** (settings) | Instead of one text blob, each answer, bookmark, position and day of activity is a proper record. That makes it fast, searchable, safe from corruption, and easy to sync. |
| Reading study files | **kotlinx.serialization** | Reads `paper.json` files. **The existing v2 format works unchanged**, so all your current material folders still import. |
| App structure | **Layered architecture** (screen → logic → data), **ViewModel + StateFlow** | Each part has one job. The screen only displays, the logic decides, the data layer stores. Bugs are easier to find, and features are easier to add without breaking others. |
| Smoothness | **Two-tier timing** (idea from SPlayer-Next) | The playhead and sentence highlight update every frame on a lightweight track; heavier screen parts update only when needed. That's the path to genuinely smooth 120 Hz. |
| Future platforms | **Kotlin Multiplatform** | Most of the code (logic, data, screens) can later run on **iOS and desktop** with little extra work. Android ships first. |
| Sync server | **Go** 1.26 + **SQLite** (or PostgreSQL) + **Docker** | A small, fast, low-memory server. It stores accounts and study progress and resolves conflicts when two devices edit at once. Anyone can self-host it with one Docker command. |
| Sync security | Token sign-in (JWT), HTTPS only, passwords hashed | Your password is never stored in readable form, and all traffic is encrypted. |
| Testing | JUnit, Compose UI tests, screenshot tests (Roborazzi), Go tests, Android emulators on GitHub | The same "robots check everything" approach, now for native screens and the server. |

### Two editions, so "offline" stays a promise

Adding sync means the app needs internet permission. Because the current app proudly has **none**, there will be two builds:

| Edition | Internet permission | Sync |
|---|---|---|
| **Listenly Offline** | **None**, same promise as today | No |
| **Listenly** (standard) | Yes, used only for sync | Optional. Nothing is sent until you sign in. |

---

## 4. Side-by-side comparison

| Topic | Old 2.0.1 | New 3.0.0 |
|---|---|---|
| What the app really is | A website inside a frame | A true Android app |
| Main language | JavaScript (+ Java frame) | Kotlin |
| Look and feel | Custom hand-made glass style | MIUIX / HyperOS design language, no Material |
| How screens update | Rebuild the whole page | Redraw only what changed |
| Day/night switch | Screenshot trick to hide redraw cost | Native, instant, smooth color animation |
| Audio engine | Browser audio + custom file pipe | Media3 / ExoPlayer, reads files directly |
| Screen-off playback | No | Yes |
| Lock screen / notification controls | No | Yes |
| Bluetooth headphone buttons | No | Yes |
| Speed change keeps natural pitch | Depends on the browser | Yes, built in |
| Data storage | One text blob in browser storage | Real database, one record per item |
| Multi-device sync | Impossible | Optional, via your own or a hosted server |
| Backup / restore | Manual | Automatic with sync, plus export to file |
| Works offline | Yes | Yes (sync is optional; Offline edition has no network at all) |
| Old-phone support | Android 8+, via code translation tools | Android 8+, natively, with no translation tools |
| iOS / desktop later | Not realistic | Realistic (shared Kotlin code) |
| Study file format | `paper.json` v2 | `paper.json` v2 (**unchanged**), with room for a v3 later |
| Your existing data | — | **Migrated automatically** on first launch |

---

## 5. Why switch? The short version

1. **It's a listening app, so audio should be first-class.** A professional audio engine fixes the class of bug that broke playback in 1.0.3, and adds background, lock-screen and headphone controls that a browser can't provide.
2. **Smoothness by design, not by tricks.** Redrawing only what changes, plus separate fast and slow update tracks, makes 120 Hz realistic. Today it depends on clever workarounds.
3. **A cohesive, polished look.** miuix provides a complete, tested HyperOS-style component set, so the app looks and feels like a modern system app instead of a styled web page.
4. **Your data becomes safe and portable.** A real database means no single fragile blob, plus easy backup, and sync across phones and tablets for those who want it.
5. **Easier to grow.** Clean layers and a modern language mean new features (vocabulary lists, dictation mode, statistics) can be added without breaking existing ones.
6. **Room to reach more devices.** The same code can later power iOS and desktop versions.

**What we give up:** the instant "open it in a desktop browser" preview, and the hand-built glass and lens effects, which must be re-created with miuix's blur tools instead of copied. We accept this because the gains above matter far more to the people actually studying with the app.

---

## 6. What the new features let you do

- **Study with the screen off.** Put your phone in your pocket and keep listening. Pause, skip and change speed from the lock screen or your earbuds.
- **Follow along like karaoke.** The current sentence lights up in sync with the audio (the "lyrics" idea from music players). Tap any sentence to jump there.
- **Pick up on another device.** Start a test on your phone, then continue on a tablet at the same spot with the same answers. (Optional sync.)
- **Never lose progress.** Answers, bookmarks, streaks and the activity calendar are stored as proper records, synced or exported to a backup file.
- **Feel at home on Xiaomi/HyperOS phones**, and look clean on every other Android phone too: rounded squircle cards, soft blur, MIUI-style settings pages, and colors that follow your wallpaper.
- **Keep everything you have today.** Intensive listening (hidden / English / bilingual), exam mode with dropdown answers, the shared timeline between modes, speeds from 1× to 2×, sequential / loop / shuffle playback, the activity calendar and folder import all carry over.
- **Self-host your own sync server** with a single Docker command, for a school, a study group, or just yourself.

---

## 7. How your existing data is kept (migration)

When version 3.0.0 is installed over 2.0.1:

1. **Your imported study folders** are already stored in the app's private storage. The new app reads them directly; nothing is copied or lost.
2. **Your answers, bookmarks, progress and statistics** live in the old browser storage. On first launch the new app opens that storage once in the background, reads everything, and moves it into the new database. A success check runs before the old copy is left behind.
3. **If anything fails**, the old data is never deleted, and the migration retries on the next launch.

> Important: this only works if 3.0.0 is signed with **the same signing key** as the installed 2.0.1 and uses a **higher `versionCode`** (16 → 300). Otherwise Android refuses to install it as an update.

---

## 8. Glossary

| Term | Plain meaning |
|---|---|
| **WebView** | A hidden mini web browser inside an Android app. |
| **Native app** | An app built directly with the phone's own tools, not running inside a browser. |
| **Kotlin** | The modern programming language Google recommends for Android. |
| **Compose** | A way of building screens from small reusable blocks that redraw only when their data changes. |
| **miuix** | An open-source toolkit that gives Compose apps the Xiaomi HyperOS look. |
| **Material** | Google's own design style. We deliberately don't use it. |
| **Media3 / ExoPlayer** | Google's professional media-playback engine. |
| **MediaSession** | The system feature that powers lock-screen and headphone controls. |
| **Room** | A database built into the app for storing structured records. |
| **Go** | A fast, simple language that's popular for servers. |
| **Docker** | A way to package a server so it runs the same on any machine with one command. |
| **Sync** | Keeping the same data on several devices automatically. |
| **versionCode** | A hidden whole number Android uses to decide whether an app is newer. It must always go up. |
| **fps / Hz** | How many times per second the screen updates. Higher means smoother (60, 90, 120). |
