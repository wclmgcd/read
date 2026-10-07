#!/bin/bash
# ============================================================
# 本脚本来自 linmax/read:latest 镜像内 /entrypoint.sh（原样保留）。
# 作用：把 /qread/ 下的 read.jar、libs/、config/ 同步到 /app（=挂载卷），
#       然后用环境变量生成 /app/conf.yml，最后执行 $JAVA_CMD 启动。
# 保留原样是为了让你原来 docker-compose.yml 里的环境变量全部继续生效。
# ============================================================
set -e

# ================== 路径定义 ==================
APP_DIR="/app"
QREAD_DIR="/qread"
CONF_DIR="$QREAD_DIR/config"
DEFAULT_CONF="$CONF_DIR/conf-sqlite.yml"
MYSQL_CONF="$CONF_DIR/conf-mysql.yml"
TARGET_CONF="$APP_DIR/conf.yml"

echo "=== Starting entrypoint.sh ==="

mkdir -p "$APP_DIR"
cp -rf "$QREAD_DIR"/* "$APP_DIR/" && rm -f "$APP_DIR/conf.yml" 2>/dev/null || true

if [ "${DB_TYPE,,}" = "mysql" ]; then
  echo "Using MySQL configuration template..."
  cp "$MYSQL_CONF" "$TARGET_CONF"
else
  echo "Using SQLite configuration template (default)..."
  cp "$DEFAULT_CONF" "$TARGET_CONF"
fi

CONF_FILE="$TARGET_CONF"

# ================== 配置规则定义（恢复default和日志配置） ==================
CONFIG_RULES=(
  # 数据库配置
  "solon.dataSources.db.jdbcUrl" "DB_JDBCURL" "solon.dataSources:" "mysql"
  "solon.dataSources.db.username" "DB_USERNAME" "solon.dataSources:" "mysql"
  "solon.dataSources.db.password" "DB_PASSWORD" "solon.dataSources:" "mysql"
  
  # Admin配置
  "admin.gonggao" "ADMIN_GONGGAO" "admin:" "default"
  "admin.username" "ADMIN_USERNAME" "admin:" "default"
  "admin.password" "ADMIN_PASSWORD" "admin:" "default"
  "admin.update" "ADMIN_UPDATE" "admin:" "default"
  "admin.code" "ADMIN_CODE" "admin:" "add"
  
  # User配置
  "user.allowchange" "USER_ALLOWCHANGE" "user:" "default"
  "user.allowuptxt" "USER_ALLOWUPTXT" "user:" "default"
  "user.allowcache" "USER_ALLOWCACHE" "user:" "default"
  "user.allowimg" "USER_ALLOWIMG" "user:" "default"
  "user.allowcheck" "USER_ALLOWCHECK" "user:" "default"
  "user.source" "USER_SOURCE" "user:" "default"
  "user.maxsource" "USER_MAXSOURCE" "user:" "default"
  "user.timeout" "USER_TIMEOUT" "user:" "default"
  "user.proxypng" "USER_PROXYPNG" "user:" "default"
  "user.index" "USER_INDEX" "user:" "default"
  
  # SMTP配置
  "smtp.host" "SMTP_HOST" "smtp:" "default"
  "smtp.protocols" "SMTP_PROTOCOLS" "smtp:" "default"
  "smtp.port" "SMTP_PORT" "smtp:" "default"
  "smtp.account" "SMTP_ACCOUNT" "smtp:" "default"
  "smtp.password" "SMTP_PASSWORD" "smtp:" "default"
  "smtp.personal" "SMTP_PERSONAL" "smtp:" "default"
  "smtp.codesubject" "SMTP_CODESUBJECT" "smtp:" "default"
  
  # default配置
  "default.tts" "DEFAULT_TTS" "default:" "default"
  "default.rule" "DEFAULT_RULE" "default:" "default"
  
  # Server配置
  "server.http.coreThreads" "SERVER_HTTP_CORETHREADS" "server.http:" "default"
  "server.http.maxThreads" "SERVER_HTTP_MAXTHREADS" "server.http:" "default"
  
  # 恢复日志配置
  "solon.logging" "DISABLE_LOG_TO_FILE" "" "log_uncomment"
)

# ================== 核心修复：精确字段查找函数 ==================
get_field_line() {
  local field_path="$1"
  local parent_node="$2"
  local field_name="${field_path##*.}"  # 只取字段名（不带冒号）
  local indent_level=0

  # 1. 查找父节点行号及缩进级别
  if [ -n "$parent_node" ]; then
    # 匹配父节点（如"admin:"），并获取其缩进空格数
    read -r parent_line indent_level <<< "$(awk -v p="$parent_node" '
      !/^[[:blank:]]*#/ {  # 跳过注释行
        if ($0 ~ "^[[:blank:]]*" p) {
          indent = length($0) - length(substr($0, index($0, p)))
          print NR, indent
          exit
        }
      }
    ' "$CONF_FILE")"
    if [ -z "$parent_line" ]; then
      return  # 父节点不存在
    fi
  fi

  # 2. 处理嵌套父节点（如solon.dataSources下的db:）
  local nested_parent=$(echo "$field_path" | awk -F '.' '{if(NF>=3) print $(NF-1) ":"}')
  if [ -n "$nested_parent" ]; then
    read -r nested_line nested_indent <<< "$(awk -v start="$parent_line" -v p="$nested_parent" -v indent="$indent_level" '
      NR > start && !/^[[:blank:]]*#/ {  # 从父节点后开始，跳过注释
        current_indent = length($0) - length(substr($0, index($0, $1)))
        if (current_indent > indent && $0 ~ "^[[:blank:]]*" p) {
          print NR, current_indent
          exit
        }
      }
    ' "$CONF_FILE")"
    if [ -n "$nested_line" ]; then
      parent_line="$nested_line"
      indent_level="$nested_indent"
    fi
  fi

  # 3. 查找目标字段（匹配缩进级别+字段名）
  awk -v start="$parent_line" -v indent="$indent_level" -v field="$field_name" '
    NR > start && !/^[[:blank:]]*#/ {  # 从父节点后开始，跳过注释
      current_indent = length($0) - length(substr($0, index($0, $1)))
      # 目标字段缩进必须比父节点大，且字段名匹配
      if (current_indent > indent && $1 == field ":") {
        print NR
        exit
      }
      # 遇到同级或上级节点则退出
      if (current_indent <= indent) exit
    }
  ' "$CONF_FILE"
}

# ================== 配置处理函数（恢复日志处理逻辑） ==================
process_config() {
  local field_path="$1"
  local env_var="$2"
  local parent_node="$3"
  local handle_type="$4"
  local env_value="${!env_var}"

  if [ -z "$env_value" ]; then
    return
  fi

  local field_name="${field_path##*.}"

  case "$handle_type" in
    "mysql")
      if [ "${DB_TYPE,,}" = "mysql" ]; then
        local line=$(get_field_line "$field_path" "$parent_node")
        if [ -n "$line" ]; then
          echo "Replacing $field_path at line $line..."
          sed -i "${line}c\    ${field_name}: ${env_value}" "$CONF_FILE"
        else
          echo "Warning: $field_path not found (MySQL mode)"
        fi
      fi
      ;;

    "default")
      local line=$(get_field_line "$field_path" "$parent_node")
      if [ -n "$line" ]; then
        # 二级字段统一2空格缩进（适配default、user等节点）
        echo "Replacing $field_path at line $line..."
        if [[ "$field_name" =~ ^(username|password|code|host|account|personal|codesubject|tts|rule)$ ]]; then
          sed -i "${line}c\  ${field_name}: \"${env_value}\"" "$CONF_FILE"
        else
          sed -i "${line}c\  ${field_name}: ${env_value}" "$CONF_FILE"
        fi
      else
        echo "Warning: $field_path not found"
      fi
      ;;

    "add")
      local line=$(get_field_line "$field_path" "$parent_node")
      if [ -n "$line" ]; then
        sed -i "${line}c\  ${field_name}: \"${env_value}\"" "$CONF_FILE"
        echo "Replacing $field_path at line $line..."
      else
        # 精确插入到父节点下（2空格缩进）
        echo "Adding $field_path = $env_value..."
        sed -i "/^[[:blank:]]*${parent_node}/a\  ${field_name}: \"${env_value}\"" "$CONF_FILE"
      fi
      ;;

    "log_uncomment")
      # 恢复日志配置解注释逻辑
      if [ "${env_value,,}" = "true" ]; then
        local log_fields=("solon.logging:" "  appender:" "    file:" "      enable: false")
        for item in "${log_fields[@]}"; do
          local line=$(awk -v pattern="#${item}" '$0 ~ pattern {print NR; exit}' "$CONF_FILE")
          if [ -n "$line" ]; then
            echo "Uncommenting log config: $item at line $line..."
            sed -i "${line}s/^#//" "$CONF_FILE"
          else
            echo "Warning: Commented $item not found"
          fi
        done
      fi
      ;;
  esac
}

# ================== 执行配置处理 ==================
echo "=== Processing configuration ==="
for ((i=0; i<${#CONFIG_RULES[@]}; i+=4)); do
  process_config \
    "${CONFIG_RULES[$i]}" \
    "${CONFIG_RULES[$i+1]}" \
    "${CONFIG_RULES[$i+2]}" \
    "${CONFIG_RULES[$i+3]}"
done

# ================== 输出最终配置 ==================
echo ""
echo "=== Final config file ==="
cat "$CONF_FILE"
echo ""

# ================== 启动应用 ==================
if [ -n "${JAVA_CMD}" ]; then
  echo "Running custom command: ${JAVA_CMD}"
  eval "${JAVA_CMD}"
else
  echo "Error: JAVA_CMD is not set"
  exit 1
fi