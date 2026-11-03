# Validation of the 1.2.0 incremental bundle

Date: 2026-10-31. This report is informational and is not part of the runtime source fingerprint.

The eight inherited contract sections in request-profiles.json (request body, GUI overrides, Planner overrides, image policy, coordinates, history, verification and budget) were compared to 1.1.0 and are identical. The incremental manifest hashes match the override files and referenced baseline files. Original 1.0.0/1.1.0 snapshots remain unchanged.

Seven dedicated JVM request-assembly cases passed in the unified build, covering the new startup metadata, literal original task, four phase templates, one-current-image/tools, unchanged Planner bodies, explicit asset inheritance, rejected malformed scope, and no credential in request bodies. The full suite passed 60 JVM cases, including new coordinator/scope/connection boundaries. This does not establish live-provider compatibility or task success.

No live-model calls are required for these checks. Device capture and dispatch checks use synthetic content and controlled results; screenshots are not persisted or exported.
