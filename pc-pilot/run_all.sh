#!/usr/bin/env bash
# pc-pilot v2 — agentic vlog pipeline end-to-end
#
# Recommended runbook on a single 16 GB GPU with LM Studio holding Qwen3.5-9B:
#   1. (LM Studio loaded, GPU has ~10 GB free) bash run_all.sh --until step2
#   2. (still LM Studio) bash run_all.sh --from step3
#
# Or run any single step:
#   bash run_all.sh --from step4 --until step4 --event evt_002

set -euo pipefail
cd "$(dirname "$0")"

UNTIL="step8"
FROM="step0"
EXTRA_PREP=""
EVENT_FILTER=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --from) FROM="$2"; shift 2 ;;
        --until) UNTIL="$2"; shift 2 ;;
        --limit) EXTRA_PREP="$EXTRA_PREP --max-assets $2"; shift 2 ;;
        --days) EXTRA_PREP="$EXTRA_PREP --days $2"; shift 2 ;;
        --event) EVENT_FILTER="--event $2"; shift 2 ;;
        --clean) EXTRA_PREP="$EXTRA_PREP --clean"; shift ;;
        *) echo "unknown arg: $1"; exit 1 ;;
    esac
done

declare -A STEP_IDX=( [prep]=-1 [step0]=0 [step1]=1 [step2]=2 [step3]=3 [step3b]=4 [step4]=5 [step5]=6 [step6]=7 [step6b]=8 [step7]=9 [step8]=10 )
UNTIL="${UNTIL/step8/step8}"; FROM="${FROM/step8/step8}"
FROM_IDX=${STEP_IDX[$FROM]:?bad --from}
UNTIL_IDX=${STEP_IDX[$UNTIL]:?bad --until}

run_step() {
    local name="$1"; shift
    local idx=${STEP_IDX[$name]}
    (( idx < FROM_IDX || idx > UNTIL_IDX )) && return
    echo
    echo "============================================================"
    echo "  $name $@"
    echo "============================================================"
    "$@"
}

# Optional: prep_inbox runs only if --clean / --days / --limit was passed and FROM <= prep
if [[ -n "$EXTRA_PREP" ]]; then
    echo "============================================================"
    echo "  prepare_inbox $EXTRA_PREP"
    echo "============================================================"
    uv run python prepare_inbox.py $EXTRA_PREP
fi

run_step step0 uv run python step0_ingest.py
run_step step1 uv run python step1_perceive.py
run_step step2 uv run python step2_segment.py
run_step step3 uv run python step3_montage.py $EVENT_FILTER
run_step step3b uv run python step3b_audience.py $EVENT_FILTER
run_step step4 uv run python step4_director.py $EVENT_FILTER
run_step step5 uv run python step5_editor.py $EVENT_FILTER
run_step step6 uv run python step6_critic.py $EVENT_FILTER
run_step step6b uv run python step6b_compose_bgm.py $EVENT_FILTER
run_step step7 uv run python step7_render.py $EVENT_FILTER --final
run_step step8 uv run python step8_index.py

echo
echo "All done. Open: workspace/index_v2.html"
