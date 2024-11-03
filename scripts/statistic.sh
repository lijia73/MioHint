#!/bin/bash

# 检查是否提供了源文件夹路径和结果文件夹路径
if [ $# -ne 2 ]; then
  echo "Usage: $0 <source_directory> <result_directory>"
  exit 1
fi

SOURCE_DIRECTORY=$1
RESULT_DIRECTORY=$2

# 创建结果文件夹（如果不存在）
mkdir -p "$RESULT_DIRECTORY"

# 遍历源文件夹中的所有文件
for file in $(find "$SOURCE_DIRECTORY" -type f); do
  # 获取文件的相对路径
  relative_path="$(basename "$file")"
  # 创建对应的结果文件路径
  result_file="$RESULT_DIRECTORY/$relative_path"

  # 创建结果文件的目录（如果不存在）
  mkdir -p "$(dirname "$result_file")"

  grep 'Config llm is' "$file"  >> "$result_file"
  grep 'Evaluated tests' "$file"  >> "$result_file"

    # 使用grep和awk提取Avg值并计算总和
  execution_time_avg=$(grep "Execution time per test" "$file" | awk -F'=' '{print $2}' | awk -F',' '{print $1}' | xargs)
  computation_overhead_avg=$(grep "Computation overhead between tests" "$file" | awk -F'=' '{print $2}' | awk -F',' '{print $1}' | xargs)

  # 计算总和
  total_avg=$(echo "$execution_time_avg + $computation_overhead_avg" | bc)

  # 输出结果
  echo "Total Avg: $total_avg" >> "$result_file"

  count_true=$(grep '!After, Cover?true' "$file" | sort | uniq | wc -l)
  count_all=$(grep '!After, Cover?' "$file" | awk -F', ' '{for (i=1; i<=NF; i++) if ($i ~ /^Target /) print $i}'| sort | uniq | wc -l)
    # 计算比例
  if [ "$count_all" -ne 0 ]; then
    ratio=$(echo "scale=4; $count_true / $count_all" | bc)
  else
    ratio="N/A"
  fi
  echo "$ratio" >> "$result_file" 

  # 将匹配的行写入结果文件
  echo "$count_true/$count_all" >> "$result_file"

  grep 'Bytecode line coverage' "$file" >> "$result_file"

  type1=$(grep -c 'LLM-assited true' "$file")
  type2=$(grep -c 'LLM-assited false' "$file")
  if [ "$type1" -ne 0 ]; then
    count_true=$(grep 'LLM-assited true' "$file"|grep -c '!After, Cover?true')
    count_all=$(grep 'LLM-assited true' "$file" |grep -c '!After, Cover?')
  elif [ "$type2" -ne 0 ]; then
    count_true=$(grep 'LLM-assited false' "$file"|grep -c '!After, Cover?true')
    count_all=$(grep 'LLM-assited false' "$file"|grep -c '!After, Cover?' )
  else 
    count_true=$(grep -c '!After, Cover?true' "$file")
    count_all=$(grep -c '!After, Cover?' "$file")
  fi
  
    # 计算比例
  if [ "$count_all" -ne 0 ]; then
    ratio=$(echo "scale=4; $count_true / $count_all" | bc)
  else
    ratio="N/A"
  fi

  echo "$ratio" >> "$result_file"

  echo "$count_true/$count_all" >> "$result_file"

  grep '!After, Cover?true' "$file" >> "$result_file"
  grep '!After, Cover?false' "$file" >> "$result_file"
done

echo "Results have been written to $RESULT_DIRECTORY"