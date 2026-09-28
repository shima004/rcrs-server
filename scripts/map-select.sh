#!/usr/bin/env bash
# Select a bundled map and launch the server from any working directory.
set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
server_root=$(cd -- "$script_dir/.." && pwd)

if [[ ${1:-} == --help || ${1:-} == -h ]]; then
    printf '使い方: %s\nマップ → 本シミュレーション／事前計算 → GUIの有無を番号で選択します。\nq または Ctrl+C で終了します。\n' "$0"
    exit 0
fi
if (( $# != 0 )); then
    printf '引数は不要です。使い方: %s --help\n' "$0" >&2
    exit 1
fi

# Set selection to a zero-based index. Do not evaluate user input as arithmetic.
choose() {
    local title=$1 answer i
    shift
    local choices=("$@")
    printf '\n%s\n' "$title" >&2
    for i in "${!choices[@]}"; do
        printf '  %d) %s\n' "$((i + 1))" "${choices[$i]}" >&2
    done
    while true; do
        printf '番号を入力（q: 終了）: ' >&2
        if ! IFS= read -r answer; then
            printf '\n終了しました。\n' >&2
            exit 0
        fi
        case "$answer" in
            q|Q) printf '終了しました。\n' >&2; exit 0 ;;
        esac
        for i in "${!choices[@]}"; do
            if [[ $answer == "$((i + 1))" ]]; then
                selection=$i
                return
            fi
        done
        printf '表示されている番号を入力してください。\n' >&2
    done
}

maps=()
map_names=()
for directory in "$server_root"/maps/*; do
    [[ -d "$directory/map" && -d "$directory/config" ]] || continue
    maps+=("$directory")
    map_names+=("${directory##*/}")
done
if (( ${#maps[@]} == 0 )); then
    printf 'maps 内に map と config ディレクトリを持つマップがありません。\n' >&2
    exit 1
fi

choose 'マップを選択してください' "${map_names[@]}"
map_dir=${maps[$selection]}
choose '実行モードを選択してください' '本シミュレーション' '事前計算'
if (( selection == 0 )); then
    mode=comprun
else
    mode=precompute
fi
if [[ $mode == comprun ]]; then
    choose '表示するウィンドウを選択してください' 'GUIあり（すべて表示）' 'GUIなし' '地図ビューアのみ'
else
    choose 'GUIの有無を選択してください' 'GUIあり' 'GUIなし'
fi
gui_options=()
gui_label=あり
if (( selection == 1 )); then
    gui_options=(--nogui)
    gui_label=なし
elif (( selection == 2 )); then
    gui_options=(--hide-gui kernel,misc,traffic,collapse,clear,fire,ignition)
    gui_label='：地図ビューアのみ'
fi

launcher="$script_dir/start-$mode.sh"
if [[ ! -f "$launcher" || ! -f "$script_dir/kill.sh" ]]; then
    printf '起動スクリプトまたは kill.sh が見つかりません。\n' >&2
    exit 1
fi

launcher_pid=
cleanup() {
    local status=$?
    trap - EXIT
    trap '' INT TERM HUP
    if [[ -n $launcher_pid ]]; then
        # Use the scripts directory: the existing launchers source functions.sh
        # and resolve their configuration paths relative to this directory.
        bash ./kill.sh --process-group "$launcher_pid" || true
        wait "$launcher_pid" 2>/dev/null || true
    fi
    exit "$status"
}

cd -- "$script_dir"
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
trap 'exit 129' HUP

printf '\n%s を %s（GUI%s）で起動します。\n' "${map_dir##*/}" "$mode" "$gui_label"
# Job control gives the launcher and its descendants their own process group.
# Waiting for a background job lets Bash handle Ctrl+C/TERM immediately.
set -m
bash "./start-$mode.sh" --map "$map_dir/map" --config "$map_dir/config" "${gui_options[@]}" &
launcher_pid=$!
set +m
wait "$launcher_pid"
