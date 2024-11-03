#!/bin/bash

# 检查是否提供了文件夹路径
if [ -z "$1" ]; then
  echo "Usage: $0 <directory>"
  exit 1
fi

DIRECTORY=$1

# 遍历文件夹中的所有文件
for file in $(find "$DIRECTORY" -type f | sort); do
  echo "$(basename "$file")"
  grep -oP '(?<=--labelForExperiments=)[^ ]+' "$file" | head -n 1
done