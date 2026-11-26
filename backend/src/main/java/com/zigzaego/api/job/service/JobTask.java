package com.zigzaego.api.job.service;

/**
 * 실행기에서 돌릴 작업 본문. 반환값은 {@code result_json} 에 JSON 으로 저장된다(null 가능).
 * 결과는 Map · record 같은 객체로 돌려줘라. String 을 돌려주면 JSON 문자열로 저장되어 화면이 객체가 아닌 글자를 받는다.
 * 사유를 화면에 알리려면 {@link JobFailedException} 을 던진다. 그 밖의 예외 · Error 는 {@code INTERNAL_ERROR} 가 된다.
 */
@FunctionalInterface
public interface JobTask {

	Object run(JobContext ctx) throws Exception;
}
