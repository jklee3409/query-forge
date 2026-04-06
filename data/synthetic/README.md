# Synthetic Data

`data/synthetic/`은 이후 단계에서 생성될 합성 질의, gating 결과, memory build 산출물을 저장하는 위치다. 현재 단계에서는 디렉터리 구조만 준비되어 있으며 실제 파일은 아직 생성되지 않았다.

## 향후 저장 예정 항목

- generation strategy별 synthetic query JSONL
- gating 전·후 결과
- memory entry export
- query type 분포 리포트

## 운영 원칙

- ungated와 gated 결과는 혼합하지 않고 분리 저장한다.
- generation strategy와 experiment 이름이 파일명 또는 메타데이터에 드러나도록 유지한다.
