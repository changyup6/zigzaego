package com.zigzaego.api.purchasedoc.service;

import com.zigzaego.api.job.entity.Job;
import com.zigzaego.api.job.entity.JobStatus;
import com.zigzaego.api.purchasedoc.entity.ParseStatus;
import com.zigzaego.api.purchasedoc.entity.PurchaseDoc;
import java.util.HashMap;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 영수증 한 장의 화면 상태 (S15P31A308-113, {@code st}). 문서 상태와 그 문서를 마지막으로 처리한 job 의 상태를 같이 본다 —
 * 서버 재시작 · 대기열 가득 등으로 job 이 끝났는데 문서가 PENDING · PROCESSING 에 남은 장(끊긴 장)은 실패로 본다.
 */
public final class ReceiptFileState {

	public static final String WAIT = "wait";
	public static final String READ = "read";
	public static final String DONE = "done";
	public static final String FAIL = "fail";
	/** 끊긴 장인데 job 에도 사유가 없을 때의 {@code error}. */
	public static final String INTERRUPTED = "RECEIPT_INTERRUPTED";

	private ReceiptFileState() {
	}

	/** job 이 아직 돌고 있거나 돌 차례다. */
	public static boolean jobActive(Job job) {
		return job != null && (job.getStatus() == JobStatus.QUEUED || job.getStatus() == JobStatus.RUNNING);
	}

	/** 읽기 전 · 읽는 중 상태. */
	private static boolean unread(PurchaseDoc doc) {
		return doc.getParseStatus() == ParseStatus.PENDING || doc.getParseStatus() == ParseStatus.PROCESSING;
	}

	/** 읽는 중 — 그 job 이 아직 돌고 있다. 지우기 · 다시 읽기를 막는다. */
	public static boolean inProgress(PurchaseDoc doc, Job job) {
		return unread(doc) && jobActive(job);
	}

	/** @param job 문서의 {@code job_id} job. 없으면 null */
	public static String st(PurchaseDoc doc, Job job) {
		return switch (doc.getParseStatus()) {
			case PENDING -> jobActive(job) ? WAIT : FAIL;
			case PROCESSING -> jobActive(job) ? READ : FAIL;
			case PARSED, CONFIRMED -> DONE;
			case FAILED -> FAIL;
		};
	}

	/** 다시 읽을 수 있다 = 화면에 실패로 보인다. */
	public static boolean failed(PurchaseDoc doc, Job job) {
		return FAIL.equals(st(doc, job));
	}

	/** 실패 사유. 끊긴 장은 job 의 사유(예 SERVER_RESTARTED), 그것도 없으면 {@link #INTERRUPTED}. */
	public static String error(PurchaseDoc doc, Job job) {
		if (unread(doc) && !jobActive(job)) {
			return job != null && job.getError() != null ? job.getError() : INTERRUPTED;
		}
		return doc.getError();
	}

	/**
	 * job 결과 {@code docs[]} 중 이 문서 항목(S15P31A308-111 {@code ReceiptAnalyzer.DocSummary} 모양). 결과가 없거나 깨졌으면 null.
	 */
	public static JsonNode summary(JsonMapper jsonMapper, Job job, long docId) {
		return summaries(jsonMapper, job).get(String.valueOf(docId));
	}

	/** job 결과 {@code docs[]} 를 문서 id(문자열) → 항목으로. 같은 id 면 먼저 나온 것. 결과가 없거나 깨졌으면 빈 맵. */
	public static Map<String, JsonNode> summaries(JsonMapper jsonMapper, Job job) {
		if (job == null || job.getResultJson() == null) {
			return Map.of();
		}
		JsonNode root;
		try {
			root = jsonMapper.readTree(job.getResultJson());
		} catch (JacksonException e) {
			return Map.of();
		}
		Map<String, JsonNode> byId = new HashMap<>();
		for (JsonNode doc : root.path("docs")) {
			JsonNode docIdNode = doc.get("id");
			if (docIdNode != null && docIdNode.isString()) {
				byId.putIfAbsent(docIdNode.asString(), doc);
			}
		}
		return byId;
	}

	/** 결과 항목의 글자 칸. 없거나 글자가 아니면 null. */
	public static String text(JsonNode summary, String field) {
		JsonNode value = summary == null ? null : summary.get(field);
		return value == null || !value.isString() ? null : value.asString();
	}
}
