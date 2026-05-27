from __future__ import annotations

import argparse
import json
import re
from collections import Counter
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import psycopg
from psycopg.types.json import Jsonb


DOMAIN_KEY = "kubernetes"
SOURCE_ID = "kubernetes-docs-current"
SOURCE_PRODUCT = "kubernetes"
SOURCE_VERSION = None

KO_DATASET_ID = "87f74f10-1e61-5c56-84f9-f70a87fba424"
KO_DATASET_KEY = "kubernetes_kr_short_user_80"
EN_DATASET_ID = "e0445e9e-7ed3-58aa-8ce1-a32d06d44a11"
EN_DATASET_KEY = "kubernetes_en_short_user_80"
VERSION_LABEL = "v2-2026-05-27"

DEFAULT_KO_OUTPUT = Path("data/eval/kubernetes_kr_short_user_test_80.jsonl")
DEFAULT_EN_OUTPUT = Path("data/eval/kubernetes_en_short_user_test_80.jsonl")

EVALUATION_FOCUS = ["grounding", "short_user", "domain_retrieval"]


@dataclass(frozen=True, slots=True)
class QuerySpec:
    url: str
    chunk_indices: tuple[int, ...]
    ko: str
    en: str
    query_type: str


@dataclass(frozen=True, slots=True)
class ChunkRecord:
    document_id: str
    chunk_id: str
    title: str
    canonical_url: str
    section_path: str
    chunk_index: int
    chunk_text: str


QUERY_SPECS: tuple[QuerySpec, ...] = (
    QuerySpec("https://kubernetes.io/docs/concepts/workloads/pods/pod-lifecycle/index.html", (0, 1), "Pod lifecycle phase 상태?", "Pod lifecycle phases?", "definition"),
    QuerySpec("https://kubernetes.io/docs/concepts/configuration/manage-resources-containers/index.html", (0, 1), "Pod 리소스 requests limits?", "Pod resource requests limits?", "procedure"),
    QuerySpec("https://kubernetes.io/docs/concepts/policy/resource-quotas/index.html", (0, 1), "ResourceQuota namespace 제한?", "ResourceQuota namespace limits?", "definition"),
    QuerySpec("https://kubernetes.io/docs/concepts/workloads/controllers/deployment/index.html", (0, 1), "Deployment rollout 업데이트?", "Deployment rollout updates?", "procedure"),
    QuerySpec("https://kubernetes.io/docs/concepts/workloads/controllers/statefulset/index.html", (0, 1), "StatefulSet identity storage 보장?", "StatefulSet identity storage?", "definition"),
    QuerySpec("https://kubernetes.io/docs/concepts/workloads/controllers/job/index.html", (0, 1), "Job 완료 실패 처리?", "Job completion failure handling?", "procedure"),
    QuerySpec("https://kubernetes.io/docs/concepts/services-networking/service/index.html", (0, 1), "Service selector ClusterIP 역할?", "Service selector ClusterIP?", "definition"),
    QuerySpec("https://kubernetes.io/docs/concepts/services-networking/ingress/index.html", (0, 1), "Ingress routing rules 설정?", "Ingress routing rules?", "definition"),
    QuerySpec("https://kubernetes.io/docs/concepts/services-networking/network-policies/index.html", (0, 1), "NetworkPolicy pod 격리?", "NetworkPolicy pod isolation?", "definition"),
    QuerySpec("https://kubernetes.io/docs/concepts/storage/persistent-volumes/index.html", (0, 1), "PV PVC binding reclaim 흐름?", "PV PVC binding reclaim?", "definition"),
    QuerySpec("https://kubernetes.io/docs/concepts/architecture/nodes/index.html", (0, 1), "Node 상태 heartbeat?", "Node status heartbeat?", "definition"),
    QuerySpec("https://kubernetes.io/docs/concepts/scheduling-eviction/taint-and-toleration/index.html", (0, 1), "taint toleration 스케줄?", "taint toleration scheduling?", "definition"),
    QuerySpec("https://kubernetes.io/docs/concepts/scheduling-eviction/kube-scheduler/index.html", (0, 1), "kube-scheduler 선택 방식?", "kube-scheduler selection?", "definition"),
    QuerySpec("https://kubernetes.io/docs/concepts/workloads/autoscaling/horizontal-pod-autoscale/index.html", (0, 1), "HPA scale 계산?", "HPA scale calculation?", "procedure"),
    QuerySpec("https://kubernetes.io/docs/reference/access-authn-authz/admission-controllers/index.html", (0, 1), "admission controller 순서?", "admission controller order?", "procedure"),
    QuerySpec("https://kubernetes.io/docs/reference/access-authn-authz/extensible-admission-controllers/index.html", (0, 1), "dynamic admission webhook 동작?", "dynamic admission webhooks?", "definition"),
    QuerySpec("https://kubernetes.io/docs/reference/access-authn-authz/authentication/index.html", (0, 1), "authentication 사용자 식별?", "authentication user identity?", "definition"),
    QuerySpec("https://kubernetes.io/docs/reference/access-authn-authz/authorization/index.html", (0, 1), "authorization mode 확인?", "authorization modes?", "definition"),
    QuerySpec("https://kubernetes.io/docs/reference/access-authn-authz/rbac/index.html", (0, 1), "RBAC RoleBinding 권한?", "RBAC RoleBinding permissions?", "definition"),
    QuerySpec("https://kubernetes.io/docs/reference/setup-tools/kubeadm/kubeadm-init/index.html", (0, 1), "kubeadm init 단계?", "kubeadm init phases?", "procedure"),
    QuerySpec("https://kubernetes.io/docs/concepts/extend-kubernetes/api-extension/custom-resources/index.html", (0, 1), "CustomResource CRD 차이?", "CustomResource CRD difference?", "definition"),
    QuerySpec("https://kubernetes.io/docs/tasks/run-application/access-api-from-pod/index.html", (0,), "Pod에서 Kubernetes API 접근?", "access Kubernetes API from Pod?", "procedure"),
    QuerySpec("https://kubernetes.io/docs/tasks/network/customize-hosts-file-for-pods/index.html", (0,), "HostAliases /etc/hosts 추가?", "HostAliases /etc/hosts entries?", "procedure"),
    QuerySpec("https://kubernetes.io/docs/concepts/workloads/pods/sidecar-containers/index.html", (0,), "sidecar container 동작?", "sidecar container behavior?", "definition"),
    QuerySpec("https://kubernetes.io/docs/concepts/workloads/pods/static-pods/index.html", (0,), "static Pod 언제?", "when static Pods?", "definition"),
    QuerySpec("https://kubernetes.io/docs/concepts/security/pod-security-standards/index.html", (0,), "Pod Security Standards profile 단계?", "Pod Security Standards profiles?", "definition"),
    QuerySpec("https://kubernetes.io/docs/concepts/security/pod-security-admission/index.html", (0,), "Pod Security Admission labels 라벨?", "Pod Security Admission labels?", "procedure"),
    QuerySpec("https://kubernetes.io/docs/tasks/run-application/configure-pdb/index.html", (0,), "PodDisruptionBudget 설정?", "PodDisruptionBudget setup?", "procedure"),
    QuerySpec("https://kubernetes.io/docs/concepts/workloads/pods/probes/index.html", (0,), "liveness readiness startup probe 차이?", "liveness readiness startup probes?", "definition"),
    QuerySpec("https://kubernetes.io/docs/tasks/configure-pod-container/configure-liveness-readiness-startup-probes/index.html", (0,), "probe HTTP TCP gRPC 설정?", "probe HTTP TCP gRPC setup?", "procedure"),
    QuerySpec("https://kubernetes.io/docs/tasks/configure-pod-container/assign-cpu-resource/index.html", (0,), "CPU request limit 할당?", "CPU requests limits assign?", "procedure"),
    QuerySpec("https://kubernetes.io/docs/tasks/configure-pod-container/assign-memory-resource/index.html", (0,), "memory request limit 할당?", "memory requests limits assign?", "procedure"),
    QuerySpec("https://kubernetes.io/docs/concepts/configuration/configmap/index.html", (0,), "ConfigMap 용도?", "ConfigMap purpose?", "definition"),
    QuerySpec("https://kubernetes.io/docs/tasks/configure-pod-container/configure-pod-configmap/index.html", (0,), "Pod ConfigMap 사용?", "Pod use ConfigMap?", "procedure"),
    QuerySpec("https://kubernetes.io/docs/concepts/configuration/secret/index.html", (0,), "Secret 민감정보 저장?", "Secret sensitive data?", "definition"),
    QuerySpec("https://kubernetes.io/docs/tasks/configmap-secret/managing-secret-using-kubectl/index.html", (0,), "kubectl Secret 생성?", "kubectl create Secret?", "procedure"),
    QuerySpec("https://kubernetes.io/docs/concepts/security/service-accounts/index.html", (0,), "ServiceAccount Pod identity 신원?", "ServiceAccount Pod identity?", "definition"),
    QuerySpec("https://kubernetes.io/docs/tasks/configure-pod-container/configure-service-account/index.html", (0,), "Pod serviceAccountName 설정?", "Pod serviceAccountName setup?", "procedure"),
    QuerySpec("https://kubernetes.io/docs/concepts/overview/working-with-objects/labels/index.html", (0,), "labels selectors 차이?", "labels selectors difference?", "definition"),
    QuerySpec("https://kubernetes.io/docs/concepts/overview/working-with-objects/annotations/index.html", (0,), "annotations 언제 씀?", "when use annotations?", "definition"),
    QuerySpec("https://kubernetes.io/docs/concepts/overview/working-with-objects/namespaces/index.html", (0,), "Namespaces 격리 범위?", "Namespaces isolation scope?", "definition"),
    QuerySpec("https://kubernetes.io/docs/tasks/administer-cluster/namespaces/index.html", (0,), "namespace로 cluster 공유?", "share cluster with namespaces?", "procedure"),
    QuerySpec("https://kubernetes.io/docs/reference/kubernetes-api/core/resource-quota-v1/index.html", (0,), "ResourceQuota API 필드?", "ResourceQuota API fields?", "definition"),
    QuerySpec("https://kubernetes.io/docs/tasks/run-application/run-stateless-application-deployment/index.html", (0,), "stateless Deployment 실행?", "run stateless Deployment?", "procedure"),
    QuerySpec("https://kubernetes.io/docs/concepts/workloads/controllers/replicaset/index.html", (0,), "ReplicaSet 역할?", "ReplicaSet role?", "definition"),
    QuerySpec("https://kubernetes.io/docs/concepts/workloads/controllers/daemonset/index.html", (0,), "DaemonSet 모든 node 실행?", "DaemonSet every node?", "definition"),
    QuerySpec("https://kubernetes.io/docs/tasks/manage-daemon/create-daemon-set/index.html", (0,), "basic DaemonSet 만들기?", "create basic DaemonSet?", "procedure"),
    QuerySpec("https://kubernetes.io/docs/concepts/workloads/controllers/cron-jobs/index.html", (0,), "CronJob schedule 동작?", "CronJob schedule behavior?", "definition"),
    QuerySpec("https://kubernetes.io/docs/concepts/workloads/controllers/ttlafterfinished/index.html", (0,), "finished Job 자동 cleanup?", "finished Job cleanup?", "procedure"),
    QuerySpec("https://kubernetes.io/docs/tasks/access-application-cluster/connecting-frontend-backend/index.html", (0,), "frontend backend Service 연결?", "frontend backend Service connection?", "procedure"),
    QuerySpec("https://kubernetes.io/docs/concepts/services-networking/dns-pod-service/index.html", (0,), "Service Pod DNS 이름?", "Service Pod DNS names?", "definition"),
    QuerySpec("https://kubernetes.io/docs/concepts/services-networking/ingress-controllers/index.html", (0,), "Ingress controller 필요?", "why Ingress controller?", "definition"),
    QuerySpec("https://kubernetes.io/docs/tasks/administer-cluster/declare-network-policy/index.html", (0,), "NetworkPolicy 선언 예시?", "declare NetworkPolicy example?", "procedure"),
    QuerySpec("https://kubernetes.io/docs/concepts/cluster-administration/networking/index.html", (0,), "cluster networking 요구사항?", "cluster networking requirements?", "definition"),
    QuerySpec("https://kubernetes.io/docs/concepts/storage/storage-classes/index.html", (0,), "StorageClass provisioner 설정?", "StorageClass provisioner?", "definition"),
    QuerySpec("https://kubernetes.io/docs/concepts/storage/dynamic-provisioning/index.html", (0,), "dynamic provisioning 언제?", "dynamic provisioning when?", "definition"),
    QuerySpec("https://kubernetes.io/docs/concepts/storage/ephemeral-volumes/index.html", (0,), "ephemeral volume 종류?", "ephemeral volume types?", "definition"),
    QuerySpec("https://kubernetes.io/docs/concepts/storage/projected-volumes/index.html", (0,), "projected volume sources 묶기?", "projected volume sources?", "definition"),
    QuerySpec("https://kubernetes.io/docs/reference/node/node-status/index.html", (0,), "NodeStatus conditions 조건?", "NodeStatus conditions?", "definition"),
    QuerySpec("https://kubernetes.io/docs/concepts/scheduling-eviction/node-pressure-eviction/index.html", (0,), "node pressure eviction 동작?", "node pressure eviction?", "definition"),
    QuerySpec("https://kubernetes.io/docs/concepts/scheduling-eviction/assign-pod-node/index.html", (0,), "Pod node 지정 방법?", "assign Pods to nodes?", "procedure"),
    QuerySpec("https://kubernetes.io/docs/tasks/configure-pod-container/assign-pods-nodes-using-node-affinity/index.html", (0,), "node affinity required preferred 차이?", "node affinity required preferred?", "definition"),
    QuerySpec("https://kubernetes.io/docs/concepts/scheduling-eviction/topology-spread-constraints/index.html", (0,), "topology spread constraints 설정?", "topology spread constraints?", "definition"),
    QuerySpec("https://kubernetes.io/docs/concepts/scheduling-eviction/scheduler-perf-tuning/index.html", (0,), "scheduler percentageOfNodesToScore 설정?", "scheduler percentageOfNodesToScore?", "procedure"),
    QuerySpec("https://kubernetes.io/docs/tasks/run-application/horizontal-pod-autoscale-walkthrough/index.html", (0,), "HPA walkthrough metrics 확인?", "HPA walkthrough metrics?", "procedure"),
    QuerySpec("https://kubernetes.io/docs/concepts/cluster-administration/node-autoscaling/index.html", (0,), "node autoscaling 구성요소?", "node autoscaling components?", "definition"),
    QuerySpec("https://kubernetes.io/docs/concepts/cluster-administration/admission-webhooks-good-practices/index.html", (0,), "admission webhook good practices 주의점?", "admission webhook good practices?", "procedure"),
    QuerySpec("https://kubernetes.io/docs/concepts/security/rbac-good-practices/index.html", (0,), "RBAC good practices 주의점?", "RBAC good practices?", "procedure"),
    QuerySpec("https://kubernetes.io/docs/reference/access-authn-authz/certificate-signing-requests/index.html", (0,), "CSR certificate 요청?", "CSR certificate requests?", "definition"),
    QuerySpec("https://kubernetes.io/docs/tasks/administer-cluster/kubeadm/kubeadm-certs/index.html", (0,), "kubeadm certs 관리?", "kubeadm certs management?", "procedure"),
    QuerySpec("https://kubernetes.io/docs/reference/setup-tools/kubeadm/kubeadm-join/index.html", (0,), "kubeadm join 토큰?", "kubeadm join token?", "procedure"),
    QuerySpec("https://kubernetes.io/docs/reference/setup-tools/kubeadm/kubeadm-upgrade/index.html", (0,), "kubeadm upgrade 절차?", "kubeadm upgrade procedure?", "procedure"),
    QuerySpec("https://kubernetes.io/docs/reference/access-authn-authz/kubelet-authn-authz/index.html", (0,), "kubelet authn authz 설정?", "kubelet authn authz?", "definition"),
    QuerySpec("https://kubernetes.io/docs/setup/production-environment/container-runtimes/index.html", (0,), "container runtime 요구사항?", "container runtime requirements?", "definition"),
    QuerySpec("https://kubernetes.io/docs/concepts/containers/runtime-class/index.html", (0,), "RuntimeClass 사용?", "RuntimeClass usage?", "definition"),
    QuerySpec("https://kubernetes.io/docs/concepts/cluster-administration/flow-control/index.html", (0,), "API Priority Fairness 동작?", "API Priority and Fairness?", "definition"),
    QuerySpec("https://kubernetes.io/docs/concepts/scheduling-eviction/api-eviction/index.html", (0,), "API eviction 동작?", "API eviction behavior?", "definition"),
    QuerySpec("https://kubernetes.io/docs/tasks/extend-kubernetes/custom-resources/custom-resource-definitions/index.html", (0,), "CRD로 API 확장?", "extend API with CRDs?", "procedure"),
    QuerySpec("https://kubernetes.io/docs/reference/kubernetes-api/apiextensions/custom-resource-definition-v1/index.html", (0,), "CustomResourceDefinition spec 필드?", "CustomResourceDefinition spec?", "definition"),
    QuerySpec("https://kubernetes.io/docs/tasks/administer-cluster/limit-storage-consumption/index.html", (0,), "storage consumption 제한?", "limit storage consumption?", "procedure"),
)

KO_ANCHOR_TRANSLATED_QUERIES: tuple[str, ...] = (
    "파드 생명주기 단계?",
    "파드 자원 요청 제한?",
    "이름공간 자원 할당량 제한?",
    "배포 점진 업데이트?",
    "상태 유지 집합 신원 저장 보장?",
    "작업 완료 실패 처리?",
    "서비스 선택자와 내부 주소 역할?",
    "인그레스 라우팅 규칙 설정?",
    "네트워크 정책 파드 격리?",
    "영구 볼륨 청구 바인딩 반환 흐름?",
    "노드 상태 신호?",
    "오염과 허용 스케줄링 기준?",
    "기본 스케줄러 선택 방식?",
    "수평 파드 자동 확장 계산?",
    "승인 제어기 실행 순서?",
    "동적 승인 웹훅 동작?",
    "인증 사용자 식별?",
    "인가 방식 확인?",
    "역할 기반 접근 제어 권한 연결?",
    "클러스터 초기화 단계?",
    "사용자 정의 리소스와 정의 차이?",
    "파드 안에서 쿠버네티스 인터페이스 접근?",
    "파드 호스트 파일 항목 추가?",
    "보조 컨테이너 동작?",
    "정적 파드 언제 사용?",
    "파드 보안 표준 단계?",
    "파드 보안 승인 라벨 기준?",
    "파드 중단 예산 설정?",
    "생존 준비 시작 확인 차이?",
    "상태 확인 방식 설정?",
    "처리장치 요청 제한 할당?",
    "메모리 요청 제한 할당?",
    "설정 맵 용도?",
    "파드에서 설정 맵 사용?",
    "비밀정보 저장?",
    "명령줄로 비밀정보 생성?",
    "서비스 계정 파드 신원?",
    "파드 서비스 계정 이름 설정?",
    "라벨과 선택자 차이?",
    "주석 언제 사용?",
    "이름공간 격리 범위?",
    "이름공간으로 클러스터 공유?",
    "자원 할당량 항목 필드?",
    "상태 없는 배포 실행?",
    "복제 집합 역할?",
    "데몬 집합 모든 노드 실행?",
    "기본 데몬 집합 만들기?",
    "일정 작업 동작?",
    "완료된 작업 자동 정리?",
    "앞단과 뒷단 서비스 연결?",
    "서비스와 파드 이름 해석?",
    "인그레스 제어기 필요?",
    "네트워크 정책 선언 예시?",
    "클러스터 네트워킹 요구사항?",
    "저장소 클래스 공급자 설정?",
    "동적 저장소 공급 언제?",
    "임시 볼륨 종류?",
    "투영 볼륨 원본 묶기?",
    "노드 상태 조건?",
    "노드 압박 축출 동작?",
    "파드 노드 지정 방법?",
    "노드 친화성 필수 선호 차이?",
    "토폴로지 분산 제약 설정?",
    "스케줄러 점수 대상 노드 비율 설정?",
    "수평 자동 확장 실습 지표 확인?",
    "노드 자동 확장 구성요소?",
    "승인 웹훅 운영 주의점?",
    "역할 기반 접근 제어 운영 주의점?",
    "인증서 서명 요청?",
    "클러스터 인증서 관리?",
    "노드 참여 토큰?",
    "클러스터 업그레이드 절차?",
    "노드 에이전트 인증 인가 설정?",
    "컨테이너 실행 환경 요구사항?",
    "실행 클래스 사용?",
    "요청 우선순위와 공정성 동작?",
    "응용 인터페이스 축출 동작?",
    "사용자 정의 리소스 정의로 인터페이스 확장?",
    "사용자 정의 리소스 정의 명세?",
    "저장소 사용량 제한?",
)


def _normalize_spaces(value: str) -> str:
    return re.sub(r"\s+", " ", value or "").strip()


def _answer_point(record: ChunkRecord) -> str:
    text = _normalize_spaces(record.chunk_text)
    marker = " - Kubernetes Documentation - "
    if marker in text:
        text = text.split(marker, 1)[1].strip()
    if len(text) > 650:
        text = text[:647].rstrip() + "..."
    return f"Section Path: {record.section_path}. {text}"


def _fetch_domain_id(connection: psycopg.Connection[Any]) -> str:
    with connection.cursor() as cursor:
        cursor.execute("SELECT domain_id::text FROM tech_doc_domain WHERE domain_key = %s", (DOMAIN_KEY,))
        row = cursor.fetchone()
    if not row:
        raise RuntimeError(f"Domain not found: {DOMAIN_KEY}")
    return row[0]


def _fetch_chunks(connection: psycopg.Connection[Any], domain_id: str) -> dict[str, list[ChunkRecord]]:
    urls = sorted({spec.url for spec in QUERY_SPECS})
    with connection.cursor() as cursor:
        cursor.execute(
            """
            SELECT
                d.canonical_url,
                d.document_id,
                c.chunk_id,
                d.title,
                c.section_path_text,
                c.chunk_index_in_document,
                c.chunk_text
            FROM corpus_documents d
            JOIN corpus_chunks c ON c.document_id = d.document_id
            WHERE d.source_id = %s
              AND d.domain_id = %s
              AND d.is_active = TRUE
              AND d.canonical_url = ANY(%s)
            ORDER BY d.canonical_url, c.chunk_index_in_document
            """,
            (SOURCE_ID, domain_id, urls),
        )
        rows = cursor.fetchall()

    chunks_by_url: dict[str, list[ChunkRecord]] = {url: [] for url in urls}
    for canonical_url, document_id, chunk_id, title, section_path, chunk_index, chunk_text in rows:
        chunks_by_url[canonical_url].append(
            ChunkRecord(
                document_id=document_id,
                chunk_id=chunk_id,
                title=title,
                canonical_url=canonical_url,
                section_path=section_path,
                chunk_index=chunk_index,
                chunk_text=chunk_text,
            )
        )

    missing = [url for url, chunks in chunks_by_url.items() if not chunks]
    if missing:
        raise RuntimeError("Missing Kubernetes documents: " + ", ".join(missing))
    return chunks_by_url


def _select_records(spec: QuerySpec, chunks_by_url: dict[str, list[ChunkRecord]]) -> list[ChunkRecord]:
    chunks = {record.chunk_index: record for record in chunks_by_url[spec.url]}
    missing = [index for index in spec.chunk_indices if index not in chunks]
    if missing:
        raise RuntimeError(f"Missing chunk indices for {spec.url}: {missing}")
    return [chunks[index] for index in spec.chunk_indices]


def _base_metadata(
    *,
    spec: QuerySpec,
    index: int,
    records: list[ChunkRecord],
    domain_id: str,
    now: str,
    dataset_key: str,
    query_language: str,
    target_method: str,
) -> dict[str, Any]:
    return {
        "updated_at": now,
        "dataset_key": dataset_key,
        "query_style": "short_user",
        "target_method": target_method,
        "query_language": query_language,
        "generation_mode": "manual_chunk_grounded_anchor_translated_short_user_v2"
        if query_language == "ko"
        else "manual_chunk_grounded_short_user_v1",
        "source_query_type": spec.query_type,
        "source_domain_id": domain_id,
        "source_ids": [SOURCE_ID],
        "source_document_ids": [record.document_id for record in records],
        "source_chunk_ids": [record.chunk_id for record in records],
        "source_canonical_urls": [record.canonical_url for record in records],
        "source_query_text": spec.en,
        "grounding_mode": "manual_current_chunk_grounding_v1",
        "query_extraction_granularity": "document_title_section_anchor_to_translated_short_user"
        if query_language == "ko"
        else "document_title_section_anchor_to_short_user",
        "degradation_reference_dataset_id": "b2d47254-8655-4c9c-81ac-7615677ec5bd",
        "sample_ordinal": index,
        "evaluation_focus": EVALUATION_FOCUS,
    }


def _build_rows(chunks_by_url: dict[str, list[ChunkRecord]], domain_id: str) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    if len(KO_ANCHOR_TRANSLATED_QUERIES) != len(QUERY_SPECS):
        raise RuntimeError(
            f"KO query override count mismatch: {len(KO_ANCHOR_TRANSLATED_QUERIES)} != {len(QUERY_SPECS)}"
        )

    now = datetime.now(timezone.utc).isoformat()
    ko_rows: list[dict[str, Any]] = []
    en_rows: list[dict[str, Any]] = []

    for index, spec in enumerate(QUERY_SPECS, start=1):
        ko_query = KO_ANCHOR_TRANSLATED_QUERIES[index - 1]
        records = _select_records(spec, chunks_by_url)
        expected_doc_ids = list(dict.fromkeys(record.document_id for record in records))
        expected_chunk_ids = [record.chunk_id for record in records]
        expected_answer_key_points = [_answer_point(record) for record in records]
        single_multi = "multi" if len(records) > 1 else "single"
        ko_target_method = "C" if single_multi == "multi" else "A"

        ko_sample_id = f"kubernetes-kr-short-user-{index:03d}"
        en_sample_id = f"kubernetes-en-short-user-{index:03d}"

        ko_metadata = _base_metadata(
            spec=spec,
            index=index,
            records=records,
            domain_id=domain_id,
            now=now,
            dataset_key=KO_DATASET_KEY,
            query_language="ko",
            target_method=ko_target_method,
        )
        ko_metadata.update(
            {
                "query_surface_language": "ko_anchor_translated_short_user",
                "paired_dataset_id": EN_DATASET_ID,
                "paired_dataset_key": EN_DATASET_KEY,
                "paired_sample_id": en_sample_id,
                "paired_user_query_en": spec.en,
                "anchor_translation_policy": "translate_or_paraphrase_english_technical_anchors_to_korean_surface",
                "translation_mode": "source_chunk_anchor_en_to_ko_anchor_translated_short_user_v2",
            }
        )

        en_metadata = _base_metadata(
            spec=spec,
            index=index,
            records=records,
            domain_id=domain_id,
            now=now,
            dataset_key=EN_DATASET_KEY,
            query_language="en",
            target_method="E",
        )
        en_metadata.update(
            {
                "query_surface_language": "en_short_user_equivalent",
                "paired_dataset_id": KO_DATASET_ID,
                "paired_dataset_key": KO_DATASET_KEY,
                "paired_sample_id": ko_sample_id,
                "paired_user_query_ko": ko_query,
                "paired_target_method": ko_target_method,
                "translation_mode": "paired_ko_short_user_to_en_equivalent_v1",
            }
        )

        common = {
            "split": "test",
            "dialog_context": {},
            "expected_doc_ids": expected_doc_ids,
            "expected_chunk_ids": expected_chunk_ids,
            "expected_answer_key_points": expected_answer_key_points,
            "query_category": "short_user",
            "difficulty": "medium",
            "single_or_multi_chunk": single_multi,
            "source_product": SOURCE_PRODUCT,
            "source_version_if_available": SOURCE_VERSION,
            "evaluation_focus": EVALUATION_FOCUS,
        }

        ko_rows.append(
            {
                "sample_id": ko_sample_id,
                "query_language": "ko",
                "user_query_ko": ko_query,
                "user_query_en": None,
                "target_method": ko_target_method,
                "metadata": ko_metadata,
                **common,
            }
        )
        en_rows.append(
            {
                "sample_id": en_sample_id,
                "query_language": "en",
                "user_query_ko": ko_query,
                "user_query_en": spec.en,
                "target_method": "E",
                "metadata": en_metadata,
                **common,
            }
        )

    return ko_rows, en_rows


def _validate_rows(ko_rows: list[dict[str, Any]], en_rows: list[dict[str, Any]], chunks_by_url: dict[str, list[ChunkRecord]]) -> dict[str, Any]:
    issues: list[str] = []
    all_rows = ko_rows + en_rows
    chunk_ids = {record.chunk_id for records in chunks_by_url.values() for record in records}

    if len(ko_rows) != 80:
        issues.append(f"ko row count mismatch: {len(ko_rows)}")
    if len(en_rows) != 80:
        issues.append(f"en row count mismatch: {len(en_rows)}")

    for language, rows in (("ko", ko_rows), ("en", en_rows)):
        queries = [row["user_query_ko"] if language == "ko" else row["user_query_en"] for row in rows]
        duplicates = [query for query, count in Counter(queries).items() if count > 1]
        if duplicates:
            issues.append(f"{language} duplicate queries: {duplicates[:5]}")
        if language == "ko":
            missing_hangul = [row["sample_id"] for row in rows if not re.search(r"[가-힣]", row["user_query_ko"])]
            if missing_hangul:
                issues.append(f"ko queries without Hangul: {missing_hangul[:5]}")
        else:
            hangul = [row["sample_id"] for row in rows if re.search(r"[가-힣]", row["user_query_en"] or "")]
            if hangul:
                issues.append(f"en queries with Hangul: {hangul[:5]}")

    for row in all_rows:
        missing_chunks = [chunk_id for chunk_id in row["expected_chunk_ids"] if chunk_id not in chunk_ids]
        if missing_chunks:
            issues.append(f"{row['sample_id']} missing chunks: {missing_chunks}")
        if len(row["expected_answer_key_points"]) != len(row["expected_chunk_ids"]):
            issues.append(f"{row['sample_id']} answer point count mismatch")

    for ko_row, en_row in zip(ko_rows, en_rows):
        if ko_row["expected_chunk_ids"] != en_row["expected_chunk_ids"]:
            issues.append(f"pair chunk mismatch: {ko_row['sample_id']} / {en_row['sample_id']}")
        if ko_row["single_or_multi_chunk"] != en_row["single_or_multi_chunk"]:
            issues.append(f"pair single/multi mismatch: {ko_row['sample_id']} / {en_row['sample_id']}")

    return {
        "status": "pass" if not issues else "fail",
        "issues": issues,
        "counts": {
            "ko": len(ko_rows),
            "en": len(en_rows),
            "total": len(all_rows),
        },
        "ko_single_multi_distribution": dict(Counter(row["single_or_multi_chunk"] for row in ko_rows)),
        "en_single_multi_distribution": dict(Counter(row["single_or_multi_chunk"] for row in en_rows)),
        "ko_target_method_distribution": dict(Counter(row["target_method"] for row in ko_rows)),
        "en_target_method_distribution": dict(Counter(row["target_method"] for row in en_rows)),
    }


def _write_jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(json.dumps(row, ensure_ascii=False) for row in rows) + "\n", encoding="utf-8")


def _dataset_distribution(rows: list[dict[str, Any]], field: str) -> dict[str, int]:
    return dict(Counter(str(row.get(field) or "") for row in rows))


def _upsert_dataset(
    connection: psycopg.Connection[Any],
    *,
    rows: list[dict[str, Any]],
    dataset_id: str,
    dataset_key: str,
    dataset_name: str,
    description: str,
    query_language: str,
    target_method: str,
    output_file: Path,
    domain_id: str,
    paired_dataset_id: str,
    paired_dataset_key: str,
) -> None:
    now = datetime.now(timezone.utc).isoformat()
    with connection.cursor() as cursor:
        cursor.execute(
            """
            INSERT INTO eval_dataset (
                dataset_id,
                dataset_key,
                dataset_name,
                description,
                version,
                split_strategy,
                total_items,
                category_distribution,
                single_multi_distribution,
                metadata,
                domain_id
            ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
            ON CONFLICT (dataset_key) DO UPDATE
            SET dataset_name = EXCLUDED.dataset_name,
                description = EXCLUDED.description,
                version = EXCLUDED.version,
                split_strategy = EXCLUDED.split_strategy,
                total_items = EXCLUDED.total_items,
                category_distribution = EXCLUDED.category_distribution,
                single_multi_distribution = EXCLUDED.single_multi_distribution,
                metadata = EXCLUDED.metadata,
                domain_id = EXCLUDED.domain_id,
                updated_at = NOW()
            """,
            (
                dataset_id,
                dataset_key,
                dataset_name,
                description,
                VERSION_LABEL,
                "test_only",
                len(rows),
                Jsonb(_dataset_distribution(rows, "query_category")),
                Jsonb(_dataset_distribution(rows, "single_or_multi_chunk")),
                Jsonb(
                    {
                        "query_language": query_language,
                        "dataset_family": "kubernetes_short_user_80",
                        "target_method": target_method,
                        "source_id": SOURCE_ID,
                        "source_product": SOURCE_PRODUCT,
                        "source_domain_id": domain_id,
                        "source_document_language": "en",
                        "paired_dataset_id": paired_dataset_id,
                        "paired_dataset_key": paired_dataset_key,
                        "pairing_policy": "KO and EN datasets share order, expected_doc_ids, and expected_chunk_ids",
                        "source_file": str(output_file).replace("\\", "/"),
                        "generation_mode": "manual_chunk_grounded_anchor_translated_short_user_v2"
                        if query_language == "ko"
                        else "manual_chunk_grounded_short_user_v1",
                        "anchor_translation_policy": "translate_or_paraphrase_english_technical_anchors_to_korean_surface"
                        if query_language == "ko"
                        else None,
                        "degradation_reference_dataset_id": "b2d47254-8655-4c9c-81ac-7615677ec5bd",
                        "evaluation_focus": EVALUATION_FOCUS,
                        "updated_at": now,
                    }
                ),
                domain_id,
            ),
        )

        for row in rows:
            cursor.execute(
                """
                INSERT INTO eval_samples (
                    sample_id,
                    split,
                    user_query_ko,
                    user_query_en,
                    query_language,
                    dialog_context,
                    expected_doc_ids,
                    expected_chunk_ids,
                    expected_answer_key_points,
                    query_category,
                    difficulty,
                    single_or_multi_chunk,
                    source_product,
                    source_version_if_available,
                    metadata,
                    domain_id
                ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
                ON CONFLICT (sample_id) DO UPDATE
                SET split = EXCLUDED.split,
                    user_query_ko = EXCLUDED.user_query_ko,
                    user_query_en = EXCLUDED.user_query_en,
                    query_language = EXCLUDED.query_language,
                    dialog_context = EXCLUDED.dialog_context,
                    expected_doc_ids = EXCLUDED.expected_doc_ids,
                    expected_chunk_ids = EXCLUDED.expected_chunk_ids,
                    expected_answer_key_points = EXCLUDED.expected_answer_key_points,
                    query_category = EXCLUDED.query_category,
                    difficulty = EXCLUDED.difficulty,
                    single_or_multi_chunk = EXCLUDED.single_or_multi_chunk,
                    source_product = EXCLUDED.source_product,
                    source_version_if_available = EXCLUDED.source_version_if_available,
                    metadata = EXCLUDED.metadata,
                    domain_id = EXCLUDED.domain_id
                """,
                (
                    row["sample_id"],
                    row["split"],
                    row["user_query_ko"],
                    row["user_query_en"],
                    row["query_language"],
                    Jsonb(row["dialog_context"]),
                    Jsonb(row["expected_doc_ids"]),
                    Jsonb(row["expected_chunk_ids"]),
                    Jsonb(row["expected_answer_key_points"]),
                    row["query_category"],
                    row["difficulty"],
                    row["single_or_multi_chunk"],
                    row["source_product"],
                    row["source_version_if_available"],
                    Jsonb(row["metadata"]),
                    domain_id,
                ),
            )

        cursor.execute("DELETE FROM eval_dataset_item WHERE dataset_id = %s", (dataset_id,))
        for row in rows:
            cursor.execute(
                """
                INSERT INTO eval_dataset_item (
                    dataset_id,
                    sample_id,
                    query_category,
                    single_or_multi_chunk,
                    active,
                    domain_id
                ) VALUES (%s, %s, %s, %s, TRUE, %s)
                """,
                (dataset_id, row["sample_id"], row["query_category"], row["single_or_multi_chunk"], domain_id),
            )


def run(
    *,
    ko_output: Path,
    en_output: Path,
    skip_db: bool,
    db_host: str,
    db_port: int,
    db_name: str,
    db_user: str,
    db_password: str,
) -> dict[str, Any]:
    with psycopg.connect(
        host=db_host,
        port=db_port,
        dbname=db_name,
        user=db_user,
        password=db_password,
        autocommit=False,
    ) as connection:
        domain_id = _fetch_domain_id(connection)
        chunks_by_url = _fetch_chunks(connection, domain_id)
        ko_rows, en_rows = _build_rows(chunks_by_url, domain_id)
        validation = _validate_rows(ko_rows, en_rows, chunks_by_url)
        if validation["status"] != "pass":
            raise RuntimeError(json.dumps(validation, ensure_ascii=False, indent=2))

        _write_jsonl(ko_output, ko_rows)
        _write_jsonl(en_output, en_rows)

        if not skip_db:
            _upsert_dataset(
                connection,
                rows=ko_rows,
                dataset_id=KO_DATASET_ID,
                dataset_key=KO_DATASET_KEY,
                dataset_name="Kubernetes KR Short User Eval 80",
                description=(
                    "Korean anchor-translated short-user evaluation dataset grounded to Kubernetes documentation chunks."
                ),
                query_language="ko",
                target_method="A/C",
                output_file=ko_output,
                domain_id=domain_id,
                paired_dataset_id=EN_DATASET_ID,
                paired_dataset_key=EN_DATASET_KEY,
            )
            _upsert_dataset(
                connection,
                rows=en_rows,
                dataset_id=EN_DATASET_ID,
                dataset_key=EN_DATASET_KEY,
                dataset_name="Kubernetes EN Short User Eval 80",
                description="English short-user companion dataset paired one-to-one with Kubernetes KR Short User Eval 80.",
                query_language="en",
                target_method="E",
                output_file=en_output,
                domain_id=domain_id,
                paired_dataset_id=KO_DATASET_ID,
                paired_dataset_key=KO_DATASET_KEY,
            )
            connection.commit()
        else:
            connection.rollback()

    return {
        "ko_output": str(ko_output),
        "en_output": str(en_output),
        "ko_dataset_id": KO_DATASET_ID,
        "en_dataset_id": EN_DATASET_ID,
        "ko_dataset_key": KO_DATASET_KEY,
        "en_dataset_key": EN_DATASET_KEY,
        "validation": validation,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Build paired Kubernetes KO/EN short-user eval datasets.")
    parser.add_argument("--ko-output", type=Path, default=DEFAULT_KO_OUTPUT)
    parser.add_argument("--en-output", type=Path, default=DEFAULT_EN_OUTPUT)
    parser.add_argument("--skip-db", action="store_true")
    parser.add_argument("--db-host", default="localhost")
    parser.add_argument("--db-port", type=int, default=5432)
    parser.add_argument("--db-name", default="query_forge")
    parser.add_argument("--db-user", default="query_forge")
    parser.add_argument("--db-password", default="query_forge")
    args = parser.parse_args()

    result = run(
        ko_output=args.ko_output,
        en_output=args.en_output,
        skip_db=args.skip_db,
        db_host=args.db_host,
        db_port=args.db_port,
        db_name=args.db_name,
        db_user=args.db_user,
        db_password=args.db_password,
    )
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
