package com.zigzaego.api.purchasedoc.ocr;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * CLOVA 키가 없을 때 쓰는 가짜 글자 인식. 받은 사진과 상관없이 정해진 마트 영수증 글자를 돌려준다.
 * 원문({@code purchase_doc.ocr_json})에 {@code "fake": true} 를 넣어 가짜임을 남긴다.
 * 키({@code CLOVA_OCR_URL} · {@code CLOVA_OCR_SECRET})를 넣으면 {@link OcrClientConfig} 가 {@link ClovaOcrClient} 로 바꾼다.
 */
public class FakeOcrClient implements OcrClient {

	/** 가짜 영수증 글자. 표 밖 글자 줄 + 표 행(탭 구분) — {@link ClovaOcrClient} 가 만드는 모양과 같다. */
	public static final String TEXT = String.join("\n",
			"가짜 OCR (CLOVA 키 없음)",
			"위글리마트",
			"판매일 2026-10-18 09:12",
			"상품명\t단가\t수량\t금액",
			"대파 1단\t3,980\t2\t7,960",
			"냉동 칵테일새우 1kg\t12,900\t1\t12,900",
			"다진마늘 500g\t5,500\t1\t5,500",
			"키친타올 6롤\t4,200\t1\t4,200",
			"합계\t\t\t30,560");

	private final JsonMapper jsonMapper;

	public FakeOcrClient(JsonMapper jsonMapper) {
		this.jsonMapper = jsonMapper;
	}

	@Override
	public OcrResult recognize(OcrImage image) {
		ObjectNode raw = jsonMapper.createObjectNode();
		raw.put("fake", true);
		raw.put("text", TEXT);
		return new OcrResult(TEXT, raw);
	}
}
