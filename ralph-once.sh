#!/bin/bash

claude --permission-mode acceptEdits "@PRD.md @progress.txt \
1. Read the PRD and progress file. \
2. Find the next incomplete task and implement it. \
3. Commit your changes. \
   Before committing, run ./gradlew build to prove everything compiles and tests pass, otherwise don't commit
4. Update progress.txt with what you did \
    - treat this as a record for you to refer back to with zero prior context. \
    After completing each task, append to progress.txt: \
    - Task completed and PRD item reference \
    - Key decisions made and reasoning \
    - Files changed \
    - Any blockers or notes for next iteration \
    Keep entries concise. Sacrifice grammar for the sake of concision. This file helps future iterations skip exploration. \
ONLY DO ONE TASK AT A TIME. "
