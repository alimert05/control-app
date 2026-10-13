# Task documents

These are the original task specifications, kept as **architectural history**. They
describe the intent, constraints and security rules of each feature; they are not
current build instructions.

macOS was the reference desktop platform when most of them were written. Windows 11 is
now the only supported desktop and the macOS agent was removed, so wherever a task says
"macOS" read it as "the desktop agent" and follow
[docs/WINDOWS_MIGRATION.md](../WINDOWS_MIGRATION.md) for what the Windows
implementation does. Each affected document carries a short note at the top.

[TASK-010](TASK-010.md) (Dashboard Modes & Profiles) was written for the Windows-only product
and is implemented; the user-facing description is in the README's *Dashboard profiles*
section and its status is tracked in [TASKS.md](../../TASKS.md).
