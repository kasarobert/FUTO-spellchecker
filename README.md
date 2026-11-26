# FUTO Keyboard – System Spellchecker

This fork of [FUTO Keyboard](https://github.com/futo-org/android-keyboard) aims to extend its existing spelling correction functionality by integrating it with Android's system-wide spellchecking framework.

## AI usage

This fork was mainly made by various AI agents, was not tested thoroughly, and was mainly made for myself.

## Goals

- **System-wide spellchecking:** Implement Android's `SpellCheckerService` to enable red underlines beneath misspelled words in supported text fields across Android applications.
- **Multilingual support:** Follow the languages configured in FUTO Keyboard, including support for simultaneous spellchecking across multiple selected languages.
- **Modular implementation:** Keep the spellchecker implementation isolated within its own package to simplify maintenance and potential integration into future FUTO Keyboard versions.

## Motivation

FUTO Keyboard already provides spelling correction suggestions but does not natively expose its spelling engine as an Android system spellchecker. Consequently, misspelled words are not highlighted with the standard red underline in applications that rely on Android's spellchecking framework.

This fork addresses that limitation by connecting FUTO's existing dictionary infrastructure to Android's `SpellCheckerService` API.
