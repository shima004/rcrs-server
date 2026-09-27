#!/usr/bin/env bash

# map-select uses an isolated process group so cleanup cannot kill another run
# or the interactive wrapper itself. Keep the legacy no-argument mode below.
if [[ ${1:-} == --process-group ]]; then
    if [[ $# != 2 || ! $2 =~ ^[1-9][0-9]*$ || $2 == 1 ]]; then
        printf 'Usage: %s --process-group <pgid>\n' "$0" >&2
        exit 1
    fi
    group=$2
    own_group=$(ps -o pgid= -p "$$") || exit 1
    own_group=${own_group//[[:space:]]/}
    if [[ $group == "$own_group" ]]; then
        printf 'Refusing to stop the caller process group.\n' >&2
        exit 1
    fi
    kill -TERM -- "-$group" 2>/dev/null || exit 0
    # Give Java shutdown hooks time to finish and close the simulation log.
    for ((attempt = 0; attempt < 50; attempt++)); do
        kill -0 -- "-$group" 2>/dev/null || exit 0
        sleep 0.1
    done
    kill -KILL -- "-$group" 2>/dev/null || true
    exit 0
fi
if (( $# != 0 )); then
    printf 'Usage: %s [--process-group <pgid>]\n' "$0" >&2
    exit 1
fi

ps -ef | grep `cd .. && pwd` | awk '{print "kill -9", $2}' | sh >/dev/null 2>&1
