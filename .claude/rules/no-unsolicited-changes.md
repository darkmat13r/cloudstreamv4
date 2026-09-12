# No Unsolicited Changes to Existing Code

**CRITICAL: Do NOT modify existing features or UI unless explicitly asked.**

## Rules

- Do NOT refactor existing screens, components, or ViewModels unless the user requests it
- Do NOT update existing UI styling, layouts, or design patterns unless told to
- Do NOT "improve" or "clean up" code in files unrelated to the current task
- Do NOT add docstrings, comments, or type annotations to code you didn't write in this task
- Do NOT change imports, formatting, or naming in existing files unless it's required by your current change
- Do NOT add error handling, validation, or fallbacks to existing code that already works

## What You CAN Do

- Add new files, classes, components, and modules
- Modify files ONLY where your current task requires changes (e.g., adding a route, registering DI)
- Fix a bug if explicitly asked
- Refactor if explicitly asked

## When Adding New Code That Touches Existing Files

- Make the minimal change needed (e.g., add one line to register a new ViewModel in DI)
- Do not reorganize, reformat, or "improve" the surrounding code
- If you notice issues in existing code, mention them to the user — don't fix them silently

## Suggest, Confirm, Then Change

When you notice any of the following in existing code, **report it and wait for user approval**:
- Style violations (naming, formatting, magic values)
- Performance issues (unnecessary allocations, missing memoization, N+1 queries)
- Hardcoded values that should use existing constants or design system tokens
- Code that duplicates existing utilities or functions elsewhere in the codebase
- Files exceeding recommended length limits

Format suggestions as:
```
Found: <what you found> in <file:line>
Suggest: <what it should be changed to>
Why: <which guideline or best practice it violates>
```
