# Manova-translator

A modern fork of **TachiyomiAT** focused on high-quality **offline AI-powered manga translation**.

This project aims to provide fast, accurate, and privacy-friendly manga translation directly on Android devices using local OCR and translation models whenever possible.

---

## Features

### Live Translation

* Real-time translation while reading.
* Translation overlay rendered directly on manga pages.
* Automatic translation when switching pages.
* Configurable page prefetch translation.
* Optimized for continuous Webtoon reading.

### OCR Engines

* ML Kit
* PaddleOCR
* ML Kit + PaddleOCR (Hybrid)

### Translation Engines

* ML Kit (On-device)
* MarianMT
* LibreTranslate
* LM Studio
* External GGUF Models
* Additional AI translation providers

### Reader Improvements

* Dedicated **Translation** tab inside Reader Settings.
* Quick translation settings without leaving the reader.
* Fast switching between OCR and translation engines.
* Source and target language selection directly inside the reader.

### Performance Improvements

* Improved page loading.
* Improved bitmap decoding.
* Cached translated pages.
* Translation prefetch for upcoming pages.
* Reduced unnecessary OCR and translation operations.

### Stability

* Fixed multiple translation rendering issues.
* Improved OCR pipeline stability.
* Improved synchronization between OCR, translation, and UI rendering.

---

## Project Goals

* High-quality local AI translation.
* Modular translation architecture.
* Easy integration of new OCR engines.
* Easy integration of new translation providers.
* Minimal latency while reading.
* Maximum privacy by supporting fully offline translation.

---

## Current OCR Support

| Engine             | Status |
| ------------------ | ------ |
| ML Kit             | ✅      |
| PaddleOCR          | ✅      |
| ML Kit + PaddleOCR | ✅      |

---

## Current Translation Engines

| Engine         | Status |
| -------------- | ------ |
| ML Kit         | ✅      |
| MarianMT       | ✅      |
| LibreTranslate | ✅      |
| LM Studio      | ✅      |
| External GGUF  | ✅      |

---

## Roadmap

* Better text bubble detection
* Improved text placement
* Faster OCR pipeline
* Additional local AI translation models
* Better font rendering
* More translation engines
* Translation cache improvements
* Performance optimizations

---

## Credits

This project is based on **TachiyomiAT**, with additional work focused on local AI translation, OCR improvements, live translation, and reader enhancements.
