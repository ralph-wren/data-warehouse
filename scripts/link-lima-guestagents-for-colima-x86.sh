#!/usr/bin/env bash
# Lima 默认 share 不含 Linux-x86_64 guest agent，Colima --arch x86_64 会报错。
# lima-additional-guestagents 把文件装在独立 prefix，需链到 lima 的 share/lima。
# brew upgrade lima 后若 Colima x86 再失败，可重新执行本脚本。

set -euo pipefail
LIMA_SHARE="$(brew --prefix lima)/share/lima"
ADD_SHARE="$(brew --prefix lima-additional-guestagents)/share/lima"
if [[ ! -d "$ADD_SHARE" ]]; then
  echo "请先安装: brew install lima-additional-guestagents" >&2
  exit 1
fi
for f in "$ADD_SHARE"/lima-guestagent.*; do
  [[ -e "$f" ]] || continue
  base=$(basename "$f")
  ln -sf "$f" "$LIMA_SHARE/$base"
  echo "OK $base -> $f"
done
echo "完成。可执行: colima delete -f && colima start --arch x86_64 ..."
