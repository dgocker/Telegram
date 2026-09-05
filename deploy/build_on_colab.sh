#!/bin/bash
# Сборка APK форка Telegram (ветка smart-feed + встроенный VPN) в Google Colab.
#
# На сервере собирать нельзя: 984 МБ RAM, а тут полный нативный билд (boringssl, ffmpeg,
# webrtc, tgcalls) под arm64. Поэтому исходники берём НЕ отсюда: Colab клонирует публичный
# форк с GitHub (~1 мин на его канале) и накатывает наш локальный дифф — в аплоад уходит
# только патч с libbox.aar, а не 1.7 ГБ дерева.
#
# Токен подписки VPN уезжает отдельной строкой в local.properties на ВМ: в гите его нет,
# в патче тоже, но в собранный APK он попадает (иначе VPN не включится сам).
#
# Использование: deploy/build_on_colab.sh
# Результат:     /root/build-artifacts/telegram-vpn-<стамп>.apk (+ build-<стамп>.log)
set -euo pipefail

REPO="${REPO:-/root/projects/telegram-android}"
BRANCH="${BRANCH:-smart-feed}"
CLONE_URL="${CLONE_URL:-https://github.com/dgocker/Telegram.git}"
VPN_TOKEN="${VPN_TOKEN:-$(sed -n 's/^VPN_SUB_TOKEN=//p' "${REPO:-/root/projects/telegram-android}/local.properties" 2>/dev/null | head -1)}"
VPN_BASE_URL="${VPN_BASE_URL:-$(sed -n 's/^VPN_BASE_URL=//p' "${REPO:-/root/projects/telegram-android}/local.properties" 2>/dev/null | head -1)}"
VPN_BASE_URL="${VPN_BASE_URL:-https://78.17.74.156:8443/}"
if [ -z "$VPN_TOKEN" ]; then
    echo "ОТКАЗ: нет VPN_SUB_TOKEN. Положи его в local.properties (файл не в гите) или передай переменной." >&2
    exit 1
fi
ART="/root/build-artifacts"
CCACHE_TGZ="$ART/tg-ccache.tgz"   # переживает смерть ВМ: следующий заход компилирует не с нуля
STAMP="$(date +%Y%m%d-%H%M%S)"
SESSION="tgvpn-$STAMP"
WORK="$(mktemp -d)"
DEADLINE_SEC="${DEADLINE_SEC:-10800}"   # полный нативный билд на 2 ядрах Colab — часы, не минуты

cd "$REPO"

BASE=$(git rev-parse "fork/$BRANCH")

echo "== Патч поверх $BASE =="
# --binary: libbox.aar лежит в патче как есть, отдельным файлом его заливать не нужно.
# Работа бывает и в коммитах (ветка vpn-inline), и в рабочем дереве — диффим ОТНОСИТЕЛЬНО
# fork/$BRANCH, который Colab и клонирует. Раньше брали только рабочее дерево, и после
# коммита цикл встал с «патч пустой» (05.09).
git add -A -- TMessagesProj TMessagesProj_App TMessagesProj_AppHockeyApp TMessagesProj_AppHuawei TMessagesProj_AppStandalone
git diff --binary "$BASE" > "$WORK/patch.diff"
git reset -q -- TMessagesProj TMessagesProj_App TMessagesProj_AppHockeyApp TMessagesProj_AppHuawei TMessagesProj_AppStandalone
if [ ! -s "$WORK/patch.diff" ]; then
    echo "ОТКАЗ: патч пустой — собирать нечего." >&2
    exit 1
fi
if grep -qF "$VPN_TOKEN" "$WORK/patch.diff"; then
    echo "ОТКАЗ: в патч просочился токен подписки — он должен жить только в local.properties." >&2
    exit 1
fi
PATCH_SHA=$(sha256sum "$WORK/patch.diff" | cut -d' ' -f1)
echo "патч: $(du -h "$WORK/patch.diff" | cut -f1), sha256 $PATCH_SHA"

mkdir -p "$ART"

cat > "$WORK/remote.py" <<PYEOF
# @title Конвейер сборки фоном (ячейка секундная)
# Длинная работа живёт в detached-процессе: клиент colab exec рвёт связь через ~10 минут
# ожидания ответа ячейки, а тут сборка на часы. Ячейки только читают файлы прогресса.
import subprocess

runner = r"""
set -e
exec >> /content/runner.log 2>&1
echo "ЭТАП: патч" > /content/progress.txt
python3 - <<'CHK'
import hashlib
got = hashlib.sha256(open("/content/patch.diff","rb").read()).hexdigest()
assert got == "$PATCH_SHA", f"патч побился: {got}"
CHK

echo "ЭТАП: инструменты" > /content/progress.txt
apt-get -qq update && apt-get -qq install -y openjdk-17-jdk-headless unzip git ccache
SDK=/content/android-sdk
mkdir -p \$SDK/cmdline-tools
if [ ! -d \$SDK/cmdline-tools/latest ]; then
    wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O /tmp/ct.zip
    unzip -q /tmp/ct.zip -d /tmp/ct
    mv /tmp/ct/cmdline-tools \$SDK/cmdline-tools/latest
fi
yes | \$SDK/cmdline-tools/latest/bin/sdkmanager --licenses > /dev/null 2>&1 || true
# cmake и платформа — мелочь, их тянет sdkmanager. NDK (≈2 ГБ) он качает в один поток и
# на медленной ВМ это съело 40 минут из часа жизни сессии (заход 1, 04.09) — берём zip
# напрямую с CDN. Версия та же, что требует gradle: 27.2.12479018 = r27c.
\$SDK/cmdline-tools/latest/bin/sdkmanager 'platform-tools' 'platforms;android-35' 'build-tools;35.0.0' 'cmake;3.22.1' > /dev/null
if [ ! -d \$SDK/ndk/27.2.12479018 ]; then
    wget -q https://dl.google.com/android/repository/android-ndk-r27c-linux.zip -O /tmp/ndk.zip
    unzip -q /tmp/ndk.zip -d /tmp/ndk
    mkdir -p \$SDK/ndk
    mv /tmp/ndk/android-ndk-r27c \$SDK/ndk/27.2.12479018
    rm -rf /tmp/ndk.zip /tmp/ndk
fi

echo "ЭТАП: клон" > /content/progress.txt
rm -rf /content/tg
git clone --depth 1 -b $BRANCH $CLONE_URL /content/tg
cd /content/tg
HEAD_SHA=\$(git rev-parse HEAD)
[ "\$HEAD_SHA" = "$BASE" ] || { echo "клон не на $BASE, а на \$HEAD_SHA"; exit 2; }

echo "ЭТАП: наложение патча" > /content/progress.txt
git apply --binary --whitespace=nowarn /content/patch.diff

echo "sdk.dir=/content/android-sdk" > local.properties
printf 'VPN_SUB_TOKEN=%s\n' '$VPN_TOKEN' >> local.properties
printf 'VPN_BASE_URL=%s\n' '$VPN_BASE_URL' >> local.properties

echo "ЭТАП: ccache" > /content/progress.txt
export CCACHE_DIR=/content/ccache
export CCACHE_MAXSIZE=6G
# те же настройки, что в рабочем CI форка: sdkmanager ставит NDK заново каждый заход,
# при дефолтной проверке по mtime кэш был бы пуст (в CI ловили 0/8068 хитов)
export CCACHE_COMPILERCHECK=content
export CCACHE_SLOPPINESS=time_macros,include_file_mtime,include_file_ctime
export CMAKE_C_COMPILER_LAUNCHER=ccache CMAKE_CXX_COMPILER_LAUNCHER=ccache
mkdir -p \$CCACHE_DIR
if ls /content/cc.part.* > /dev/null 2>&1; then
    cat /content/cc.part.* > /content/ccache.tgz && rm -f /content/cc.part.*
fi
if [ -f /content/ccache.tgz ]; then
    tar -xzf /content/ccache.tgz -C /content || true
fi
ccache -z > /dev/null 2>&1 || true

# Бесплатную ВМ утилизируют посреди билда (две попытки 03-04.09: 35 и 58 минут), поэтому
# кэш компилятора выкладываем снапшотами: хост забирает последний и подкладывает
# в следующий заход. Пишем через .tmp+mv, чтобы хост не утащил полуготовый архив.
# Кэш отдаём кусками: цельные 400+ МБ хост скачать не может — клиент colab держит файл
# целиком в памяти и на сервере с 984 МБ ловит OOM-killer (05.09, 757 МБ RSS).
# Снапшоты кладём в ДВА чередующихся каталога: пока хост качает один, следующий пишется
# в другой. Раньше снапшот перезаписывал файлы прямо под скачиванием, md5 не сходился —
# за 11 заходов (05.09) в кэш не попало ни байта, он замёрз на версии от 00:51.
SNAP_SLOT=a
snapshot_ccache() {
    if [ "\$SNAP_SLOT" = "a" ]; then SNAP_SLOT=b; else SNAP_SLOT=a; fi
    D=/content/snap-\$SNAP_SLOT
    rm -rf \$D && mkdir -p \$D
    tar -czf \$D/ccache.tgz -C /content ccache 2>/dev/null || return 0
    ( cd \$D && split -b 20m ccache.tgz part. && { md5sum ccache.tgz | cut -d' ' -f1; ls -1 part.*; } > list && rm -f ccache.tgz )
    echo "snap-\$SNAP_SLOT" > /content/cc.latest
    ccache -s 2>/dev/null | head -6 > /content/ccstats.txt
}

(
  while :; do
    sleep 600
    snapshot_ccache
  done
) &
SNAP_PID=\$!

echo "ЭТАП: gradle" > /content/progress.txt
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ANDROID_HOME=\$SDK ANDROID_SDK_ROOT=\$SDK
# nice: сборке всё равно, а ядро Jupyter остаётся отзывчивым для файлового API
RC=0
nice -n 19 ./gradlew :TMessagesProj_AppStandalone:assembleAfatStandalone --no-daemon --console=plain > /content/build.log 2>&1 || RC=\$?
kill \$SNAP_PID 2>/dev/null || true
ccache -s >> /content/build.log 2>&1 || true
snapshot_ccache
echo "КОД=\$RC" > /content/progress.txt
"""
open("/content/runner.sh", "w").write(runner)
subprocess.Popen("bash /content/runner.sh || echo КОД=1 > /content/progress.txt",
                 shell=True, start_new_session=True)
print("конвейер запущен фоном на ВМ")
PYEOF

# Сессию НЕ гасим по trap EXIT: 03.09 оркестратор убили таймаутом харнеса, и trap
# унёс бы с собой живую сборку на ВМ. Раннер detached — он переживает смерть этого
# скрипта, и досмотреть сборку можно вторым процессом (см. SESSION_FILE ниже).
SESSION_FILE="$ART/last-colab-session"
trap 'rm -rf "$WORK"' EXIT
finish() { colab stop -s "$SESSION" >/dev/null 2>&1 || true; }

# Осиротевшие сессии прошлых заходов держат слоты, и Colab отвечает TooManyAssignments
# на новую ВМ (04.09: два зомби после сорванной заливки — и три захода подряд в никуда).
# Заходы идут последовательно, поэтому глушить всё из списка безопасно.
SESSION_LIST="$ART/colab-sessions.list"
if [ -f "$SESSION_LIST" ]; then
    while read -r old_session; do
        [ -n "$old_session" ] || continue
        timeout 60 colab stop -s "$old_session" >/dev/null 2>&1 || true
    done < "$SESSION_LIST"
    : > "$SESSION_LIST"
fi

echo "== ВМ =="
echo "$SESSION" >> "$SESSION_LIST"
colab new -s "$SESSION"
printf '%s %s\n' "$SESSION" "$STAMP" > "$SESSION_FILE"
echo "== Заливка патча =="
colab upload -s "$SESSION" "$WORK/patch.diff" /content/patch.diff
if [ -f "$CCACHE_TGZ" ]; then
    # Цельные 100+ МБ через файловый API Colab рвутся по SSL (заходы 3-5 04.09 умерли
    # ровно на этом, потому что set -e уносил весь заход). Шлём кусками, с ретраями,
    # и провал заливки больше не смертелен: хуже всего — заход компилирует с нуля.
    echo "== Заливка ccache прошлого захода ($(du -h "$CCACHE_TGZ" | cut -f1)) =="
    split -b 20m "$CCACHE_TGZ" "$WORK/cc.part."
    CC_OK=1
    for part in "$WORK"/cc.part.*; do
        NAME=$(basename "$part")
        PART_OK=0
        for attempt in 1 2 3; do
            if timeout 300 colab upload -s "$SESSION" "$part" "/content/$NAME" >/dev/null 2>&1; then
                PART_OK=1
                break
            fi
            sleep 10
        done
        [ "$PART_OK" -eq 1 ] || { CC_OK=0; echo "  кусок $NAME не залился — заход пойдёт без кэша"; break; }
    done
    if [ "$CC_OK" -eq 1 ]; then
        echo "  ccache залит ($(ls "$WORK"/cc.part.* | wc -l) кусков)"
    fi
else
    echo "== ccache пуст: первый заход компилирует с нуля =="
fi
echo "== Запуск конвейера =="
colab exec -s "$SESSION" -f "$WORK/remote.py"

echo "== Поллинг каждые 60с (дедлайн $((DEADLINE_SEC/60)) мин) =="
# Прогресс читаем файловым API (colab download), а не исполнением ячейки: во время сборки
# kernel голодает, потерянный ответ вешает клиента навечно.
DEADLINE=$(( $(date +%s) + DEADLINE_SEC ))
TICK=0
MISSES=0
while :; do
    [ "$(date +%s)" -lt "$DEADLINE" ] || { echo "ОТКАЗ: не уложились в дедлайн" >&2; finish; exit 1; }
    sleep 60
    TICK=$((TICK + 1))
    # Раз в 4 минуты дёргаем ядро копеечной ячейкой: без этого Colab считает рантайм
    # простаивающим (сборка-то в detached-процессе) и гасит ВМ посреди билда — так
    # умерла первая попытка 03.09. Ответ не обязателен, потому best-effort.
    if [ $((TICK % 4)) -eq 0 ]; then
        timeout 60 colab exec -s "$SESSION" -f "$REPO/deploy/keepalive.py" --timeout 20 >/dev/null 2>&1 || true
    fi
    rm -f "$WORK/progress.txt"
    if timeout 60 colab download -s "$SESSION" /content/progress.txt "$WORK/progress.txt" >/dev/null 2>&1; then
        MISSES=0
    else
        MISSES=$((MISSES + 1))
        # Пять глухих опросов подряд = ВМ, скорее всего, больше нет. Молча ждать дедлайна
        # бессмысленно: раньше наблюдатель так простоял 3 часа над мёртвой сессией.
        if [ "$MISSES" -ge 5 ]; then
            echo "ОТКАЗ: сессия $SESSION не отвечает 5 опросов подряд — ВМ, похоже, утилизирована." >&2
            colab ls 2>&1 | tail -3 >&2
            exit 1
        fi
    fi
    LINE=$(tail -1 "$WORK/progress.txt" 2>/dev/null || true)
    echo "  $(date +%H:%M:%S) ${LINE:-<нет ответа>}"
    # Кэш забираем дважды за заход (на 30-й и 50-й минуте): полный снимок едет ~10 минут,
    # чаще — только мешать сборке и гоняться за собственным хвостом.
    if [ "$TICK" = "30" ] || [ "$TICK" = "50" ]; then
        rm -rf "$WORK/cc"; mkdir -p "$WORK/cc"
        if timeout 60 colab download -s "$SESSION" /content/cc.latest "$WORK/cc/latest" >/dev/null 2>&1; then
            SNAP=$(tr -d '\r\n' < "$WORK/cc/latest")
            if timeout 120 colab download -s "$SESSION" "/content/$SNAP/list" "$WORK/cc/list" >/dev/null 2>&1; then
                WANT_MD5=$(head -1 "$WORK/cc/list")
                PARTS_OK=1
                for name in $(tail -n +2 "$WORK/cc/list"); do
                    GOT=0
                    for attempt in 1 2; do
                        timeout 300 colab download -s "$SESSION" "/content/$SNAP/$name" "$WORK/cc/$name" >/dev/null 2>&1 \
                            && [ -s "$WORK/cc/$name" ] && { GOT=1; break; }
                        sleep 5
                    done
                    [ "$GOT" -eq 1 ] || { PARTS_OK=0; break; }
                done
                if [ "$PARTS_OK" -eq 1 ]; then
                    cat "$WORK"/cc/part.* > "$WORK/cc/ccache.new" 2>/dev/null
                    if [ "$(md5sum "$WORK/cc/ccache.new" | cut -d' ' -f1)" = "$WANT_MD5" ]; then
                        mv "$WORK/cc/ccache.new" "$CCACHE_TGZ"
                        echo "  ccache снят из $SNAP: $(du -h "$CCACHE_TGZ" | cut -f1)"
                    else
                        echo "  ccache: md5 не сошёлся, пропускаю"
                    fi
                fi
            fi
        fi
        rm -rf "$WORK/cc"
        # Статистика ядра: без неё не видно, попадает ли кэш вообще (а он может и мимо).
        timeout 60 colab download -s "$SESSION" /content/ccstats.txt "$WORK/ccstats.txt" >/dev/null 2>&1 \
            && sed 's/^/  ccache: /' "$WORK/ccstats.txt" | head -4
    fi
    case "$LINE" in
        КОД=0) break ;;
        КОД=*)
            echo "ОТКАЗ: сборка упала — качаю логи" >&2
            timeout 180 colab download -s "$SESSION" /content/build.log "$ART/build-$STAMP-FAILED.log" || true
            timeout 60 colab download -s "$SESSION" /content/runner.log "$ART/runner-$STAMP-FAILED.log" || true
            echo "логи: $ART/build-$STAMP-FAILED.log, $ART/runner-$STAMP-FAILED.log" >&2
            finish
            exit 1 ;;
    esac
done

echo "== Скачивание APK сразу (простаивающую ВМ Colab утилизирует) =="
APK="$ART/telegram-vpn-$STAMP.apk"
colab download -s "$SESSION" /content/tg/TMessagesProj_AppStandalone/build/outputs/apk/afat/standalone/app.apk "$APK"
timeout 180 colab download -s "$SESSION" /content/build.log "$ART/build-$STAMP.log" || true
# финальный кэш забирать цельным нельзя (OOM), но он и так снят последним поллингом

echo "apk:    $APK"
echo "размер: $(du -h "$APK" | cut -f1)"
echo "sha256: $(sha256sum "$APK" | cut -d' ' -f1)"
finish
echo "ГОТОВО"
