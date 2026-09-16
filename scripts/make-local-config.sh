#!/usr/bin/env bash
# Regenerate config/application-local.yml from docker/atlas/.env.atlas.
#
# Why it exists: orchstack.sh exports .env.atlas into the JVM it launches, but
# the IDE's Run button does not read that file — the app would come up with an
# empty spring.r2dbc.username and die on "No value found for user". Spring Boot
# reads ./config/application-local.yml on its own when the `local` profile is
# active, so the IDE run config only has to name the profile.
#
# The generated file holds real credentials and is gitignored.
set -euo pipefail

cd "$(dirname "$0")/.."
ENV_FILE="docker/atlas/.env.atlas"
OUT="config/application-local.yml"

[ -f "$ENV_FILE" ] || { echo "missing $ENV_FILE" >&2; exit 1; }

python3 - "$ENV_FILE" "$OUT" <<'PY'
import pathlib, sys

env_file, out_path = sys.argv[1], sys.argv[2]
env = {}
for line in pathlib.Path(env_file).read_text().splitlines():
    line = line.strip()
    if not line or line.startswith('#') or '=' not in line:
        continue
    k, v = line.split('=', 1)
    env[k] = v.strip().strip('"').strip("'")

def g(key, default=''):
    return env.get(key, default)

accounts, i = [], 0
while f'TELEGRAM_ACCOUNTS_{i}_BOTID' in env:
    accounts.append({f: g(f'TELEGRAM_ACCOUNTS_{i}_{f.upper()}')
                     for f in ('botId', 'name', 'apiId', 'apiHash', 'phoneNumber', 'sessionsDirectory')})
    i += 1

host, port, db = g('DATABASE_HOST', 'localhost'), g('DATABASE_PORT', '5433'), g('DATABASE_NAME', 'news_aggregator_db')
user, password = g('DATABASE_USERNAME', 'staging'), g('DATABASE_PASSWORD', 'staging')

acc = ''
for a in accounts:
    acc += f"    - botId: \"{a['botId']}\"\n"
    if a['name']:
        acc += f"      name: \"{a['name']}\"\n"
    acc += (f"      apiId: {a['apiId']}\n"
            f"      apiHash: \"{a['apiHash']}\"\n"
            f"      phoneNumber: \"{a['phoneNumber']}\"\n"
            f"      sessionsDirectory: \"{a['sessionsDirectory']}\"\n")

pathlib.Path(out_path).parent.mkdir(exist_ok=True)
pathlib.Path(out_path).write_text(f"""# Local run settings for the IDE (profile "local", loaded from ./config/).
# Gitignored: it holds real credentials. Mirrors docker/atlas/.env.atlas, which
# only the orchstack.sh path reads. Regenerate with scripts/make-local-config.sh.

server:
  port: {g('SERVER_PORT', '8099')}

app:
  http:
    enabled: {g('APP_HTTP_ENABLED', 'true')}

spring:
  r2dbc:
    url: r2dbc:postgresql://{host}:{port}/{db}?schema=tgscan,bot,public
    username: {user}
    password: {password}
  liquibase:
    url: jdbc:postgresql://{host}:{port}/{db}?currentSchema=bot
    user: {user}
    password: {password}
  kafka:
    bootstrap-servers: {g('KAFKA_BOOTSTRAP_SERVERS', '127.0.0.1:9094')}

qdrant:
  url: {g('MEMO_QDRANT_URL', 'http://127.0.0.1:6335')}

embedding:
  url: {g('EMBEDDING_URL', 'http://127.0.0.1:8087')}

deepseek:
  apiKey: {g('DEEPSEEK_API_KEY')}
  model: {g('DEEPSEEK_MODEL', 'deepseek-flash')}

bot:
  persona-ids: "{g('BOT_PERSONA_IDS')}"

telegram:
  # New accounts log in headless: TDLib sends the code to the phone from this
  # file, you POST it to /api/admin/auth/<botId>/code.
  auth:
    headless:
      enabled: true
  allowedCommandChatId: {g('TELEGRAM_ALLOWEDCOMMANDCHATID', '0')}
  sharedFilesDirectory: "{g('TELEGRAM_SHAREDFILESDIRECTORY')}"
  accounts:
{acc}
logging:
  level:
    com.example.telegramuserbot: {g('LOG_LEVEL_APP', 'INFO')}
""")
print(f"{out_path}: {len(accounts)} account(s) — {', '.join(a['botId'] for a in accounts)}")
PY
