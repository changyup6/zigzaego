package com.zigzaego.api.job.service;

/** 작업 본문이 진행 상황을 알리는 통로. */
public interface JobContext {

	long jobId();

	/**
	 * 진행률(0~1)을 기록한다. 값이 줄어드는 것은 허용한다.
	 *
	 * @throws IllegalArgumentException 0~1 밖이거나 숫자가 아니면. 작업은 FAILED 가 된다
	 */
	void progress(double progress);

	/**
	 * 끝나기 전에 화면에 보여줄 중간 결과를 {@code result_json} 에 덮어쓴다(RUNNING 일 때만). 끝나면 반환값이 다시 덮는다.
	 * 예: 온보딩이 수집을 마치면 메뉴 이름 목록 (FE {@code analysis.ts} COLLECT_DONE).
	 * Map · record 같은 객체로 넘겨라 — String 은 JSON 문자열로 저장된다.
	 */
	void partialResult(Object partial);
}
