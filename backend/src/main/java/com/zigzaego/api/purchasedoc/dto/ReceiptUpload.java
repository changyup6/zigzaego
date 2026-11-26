package com.zigzaego.api.purchasedoc.dto;

/**
 * 올린 영수증 파일 한 장. 컨트롤러가 multipart 파일을 이 모양으로 바꿔 {@code ReceiptJobService.start} 에 넘긴다.
 *
 * @param fileName    올린 파일 이름 (화면 표시용, 결과 docs[].file)
 * @param contentType {@code image/jpeg} · {@code image/png} · {@code application/pdf}
 * @param bytes       파일 내용
 */
public record ReceiptUpload(String fileName, String contentType, byte[] bytes) {
}
