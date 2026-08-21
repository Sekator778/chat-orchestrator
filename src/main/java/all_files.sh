#!/usr/bin/env bash
set -euo pipefail

output_file="all_java_code.txt"

# Remove previous result if exists
if [ -f "$output_file" ]; then
  echo "Removing existing output file: $output_file"
  rm "$output_file"
fi

echo "Collecting Java source code into $output_file, prepending paths..."
echo "Running from: $(pwd)"
echo "Skipping .git, .idea, .gradle, build, out directories (recursively)."

# Find all .java files below current dir, skipping unwanted dirs
find . \
  -type d \( -name ".git" -o -name ".idea" -o -name ".gradle" -o -name "build" -o -name "out" \) -prune -o \
  -type f -iname "*.java" -print0 \
  | while IFS= read -r -d '' filepath; do
      # Normalize path to start with ./
      relpath="./${filepath#./}"

      {
        echo "--- File: $relpath ---"
        cat "$filepath"
        echo    # blank line between files
      } >> "$output_file"
    done

if [ -s "$output_file" ]; then
  echo "Done. Java source code with paths saved to $output_file"
else
  echo "Warning: $output_file is empty. No Java files found (or an error occurred)."
fi

exit 0
