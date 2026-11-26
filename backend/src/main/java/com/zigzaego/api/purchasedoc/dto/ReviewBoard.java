package com.zigzaego.api.purchasedoc.dto;

import java.util.List;

/**
 * 영수증 확인 목록 (S15P31A308-113). 칸 이름은 {@code GET /receipts} 응답 중 묶음 · 재료 선택지와
 * 같다 — 컨트롤러가 장 목록({@code files})을 합친다.
 *
 * @param matches   확인 전 줄을 상품명 하나당 하나로 묶은 것. 최근 줄이 있는 묶음 먼저
 * @param options   이 가게 재료 전부(재료 id 오름차순) — 묶음마다 고를 수 있는 재료
 * @param readyDocs 줄이 모두 자동 확정돼 사장님이 확정만 하면 되는 문서({@code confirmDocuments} 로 확정)
 */
public record ReviewBoard(List<ReviewMatchResponse> matches, List<ReviewOptionResponse> options, List<ReadyDocResponse> readyDocs) {
}
