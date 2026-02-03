#!/bin/bash
set -e

if [ -z "$1" ]; then
  echo "Usage: $0 <iterations>"
  exit 1
fi

# Extract streaming assistant text chunks
stream_text='select(.type == "assistant").message.content[]? | select(.type == "text").text // empty | gsub("\n"; "\r\n") | . + "\r\n\n"'

# Extract final result payload
final_result='select(.type == "result").result // empty'

for ((i=1; i<=$1; i++)); do
  echo "---- iteration $i ----"

  tmpfile="$(mktemp -t ralph_stream.XXXXXX)"
  trap 'rm -f "$tmpfile"' EXIT

  claude \
    --permission-mode acceptEdits \
    --verbose \
    --print \
    --output-format stream-json \
    "@PRD.md @progress.txt \
1. Find the highest-priority task and implement it. \
2. Run your tests and type checks. \
3. Update the PRD with what was done. \
4. Append your progress to progress.txt. \
5. Commit your changes. \
ONLY WORK ON A SINGLE TASK. \
If the PRD is complete, output <promise>COMPLETE</promise>." \
  \
  | perl -ne 'BEGIN { $|=1 } print if /^[{]/' \
  | tee "$tmpfile" \
  | jq --unbuffered -rj "$stream_text"

  result="$(jq -r "$final_result" "$tmpfile")"
  echo "$result"

  if [[ "$result" == *"<promise>COMPLETE</promise>"* ]]; then
    echo "PRD complete after $i iterations."
    exit 0
  fi

  rm -f "$tmpfile"
done
