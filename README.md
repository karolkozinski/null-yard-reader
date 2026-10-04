# Null Reader

**Null Reader** is a small, offline-first Android reader focused on one thing: opening a book or document and reading it without accounts, ads, cloud sync, social features, or unnecessary UI.

The project is part of the wider **Null Yard** collection of small, practical tools.

Project website: **https://nullreader.nullyard.com**

Current beta APK: **https://nullreader.nullyard.com/downloads/null-reader-0.3-beta4.apk**

> **Status:** beta / active development.  
> The app is already usable, but the interface and file handling are still being refined.

## What it reads

- **EPUB** — rendered locally, with reading-position restore
- **PDF** — native Android rendering, page navigation, saved page and pinch-to-zoom
- **Markdown** — lightweight local rendering
- **TXT** — plain-text reading

The library is split into separate **EPUB**, **PDF**, and **Text** views so each format stays simple and uncluttered.

## Reading features

- Remembers your last reading position
- Remembers the last PDF page
- Adjustable text size
- Adjustable line spacing
- Adjustable page margins
- Live reading preview
- Three reading profiles:
  - Dark
  - Light
  - Compass — a warmer, paper-like theme inspired by the Martwy Kompas project
- Fresh installs default to Compass, 17 px text, 160% line spacing and 20 dp margins
- System Back gesture returns from the reader to the library
- Removing a book from the library never deletes the source file

## Privacy and storage

Null Reader is designed to stay local.

- No account
- No ads
- No analytics
- No tracking
- No cloud backend
- No DRM
- No broad storage permission

Files are opened through Android's **Storage Access Framework**. The app keeps persisted document permissions and reading state, but does not copy imported books into a private internal library.

## Android approach

The project deliberately keeps the stack small.

- Java
- Android Views
- Android Storage Access Framework
- Android `PdfRenderer`
- `WebView` only for rendering book/document HTML
- No JavaScript in the reader
- No large ebook framework or PDF dependency

EPUB files are parsed directly from their ZIP structure: `container.xml` → OPF manifest/spine → XHTML content.

## Requirements

- Android 9 (API 28) or newer
- Android SDK 36 for building the current project
- JDK compatible with the configured Android Gradle Plugin

Application ID:

```text
com.nullyard.reader
```

## Build

Clone the repository and build the debug APK:

```bash
git clone https://github.com/karolkozinski/null-yard-reader.git
cd null-yard-reader
./gradlew assembleDebug
```

The APK will be created under:

```text
app/build/outputs/apk/debug/
```

You can also open the project directly in Android Studio.

## Current limitations

This is an early beta build.

- EPUB rendering is intentionally lightweight rather than a full EPUB engine
- Very large EPUB files have not yet been heavily optimized
- PDF reading is page-based; mobile PDFs remain constrained by fixed page layouts
- PDF zoom is functional but still being polished
- Android **Open with…** integration is available for EPUB, PDF, Markdown and TXT
- EPUB title/author metadata is read from OPF where available; unusual metadata remains an edge case

## Near-term roadmap

1. More polished library UI
2. Additional visual refinement of reading profiles
3. Wider device testing and bug fixing
4. Better handling of edge-case EPUB metadata and documents
5. Release packaging and distribution polish

The goal is not to become a giant ebook-management platform. The goal is to remain a fast, quiet reader that gets out of the way.

## Project philosophy

Null Reader is intentionally boring in the useful sense:

**open file → read → close app**

Everything else has to justify its existence.
