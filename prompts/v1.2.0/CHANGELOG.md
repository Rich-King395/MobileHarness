# 1.2.0: automatic Home entry with a reusable authorized App scope

Date: 2026-10-31.

This is an explicit incremental bundle over the immutable 1.1.0 baseline. It is not a claim of identical inputs or identical experimental conditions.

Changed runtime sources are listed in `manifest.json`. The four GUI phase task templates now identify HOME_AUTO, the actual initial Home package, a separate navigation-only Home package, and the authorized App package/display-label mapping. The GUI system text states the Home navigation restriction. Display labels identify Apps and do not redefine the task. The user's original task remains verbatim.

The request profile's version and credential-source metadata identify this revision and the encrypted configuration library. Qwen request-body values, phone tool schemas, coordinate and image conventions, GUI termination, control-return notice, Planner prompts/schemas, history policy, shared budget and recovery rules inherit 1.1.0 unchanged.

HOME_AUTO uses this 1.2.0 bundle. Legacy SELECTED_APP debug/regression sessions continue to use 1.1.0. Runtime source paths use an explicit override allowlist; a missing override fails rather than silently falling back to an older prompt. Session-start metadata records the selected version and its manifest hash without App names, task text or keys.

The executor checks scope when observing and acting. On Home, normal execution can launch an authorized App, use Home/Back, wait or finish; VERIFY/CONFIRM still only wait/finish. These checks are not proof of strict per-App image isolation: Android 13 captures the whole display, including possible overlays, and foreground package checks cannot prove the absence of transient windows. No cropping, painting, screenshot history or human-period polling is added.
