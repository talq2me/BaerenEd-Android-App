# Format Unification Status

## Goal
Unify to cloud format everywhere: `Map<String, TaskProgress>` with task **names** (not IDs) as keys.

## Status: IN PROGRESS

This is a major refactoring that will:
- Eliminate ~550 lines of conversion code
- Simplify the codebase significantly
- Make sync logic trivial (direct copy)

## Steps

- [ ] Step 1: Create migration utility (TaskProgressMigration.kt)
- [ ] Step 2: Update DailyProgressManager storage format
- [ ] Step 3: Update IProgressManager interface
- [ ] Step 4: Update all callers (Layout, TaskCompletionHandler, etc.)
- [ ] Step 5: Simplify CloudStorageManager
- [ ] Step 6: Remove conversion code
- [ ] Step 7: Test and verify

## WARNING

This refactoring touches many files and will require thorough testing. Proceeding carefully and incrementally.
