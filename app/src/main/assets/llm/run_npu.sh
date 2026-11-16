#!/system/bin/sh
set -eu
cd "$(dirname "$0")"
case "$(uname -m)" in aarch64|arm64) ;; *) echo "ARM64 보드에서 실행하세요" >&2; exit 1;; esac
export LD_LIBRARY_PATH="$PWD:/vendor/lib64${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
export ADSP_LIBRARY_PATH="$PWD${ADSP_LIBRARY_PATH:+;$ADSP_LIBRARY_PATH};/usr/lib/rfsa/adsp;/vendor/lib/rfsa/adsp;/system/lib/rfsa/adsp;/dsp"
exec ./genie-t2t-run -c genie_config_npu.json -p "$(cat prompt.txt)" --profile profile.txt
