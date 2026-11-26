package com.zigzaego.api.global.store;

import com.zigzaego.api.global.exception.BusinessException;
import com.zigzaego.api.global.exception.CommonErrorCode;
import java.util.Optional;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.Repository;

/**
 * 가게에 속한 표({@code store_id} 칸이 있는 표)의 리포지토리가 상속한다 (ADR-0002).
 *
 * <p>{@code JpaRepository} 대신 {@link Repository}를 상속해 가게 조건 없는 {@code findById} · {@code findAll} ·
 * {@code deleteById}가 아예 생기지 않게 한다. 여기 없는 메서드는 이름에 {@code StoreId}를 넣어(파생 쿼리) 직접 선언한다.
 * 가게 조건 없이 써야 하는 내부 경로(예: job 실행기의 상태 갱신)는 {@link StoreScopeExempt}로 이유를 남긴다.
 * {@code StoreScopeGuardTest}가 위 규칙을 모든 리포지토리에 검사한다.
 */
@NoRepositoryBean
public interface StoreScopedRepository<T> extends Repository<T, Long> {

	<S extends T> S save(S entity);

	/** {@link #getInStore}로 읽은 엔티티만 넘긴다 — 엔티티를 이미 가게로 좁혀 읽었으니 삭제도 가게 스코프다. */
	void delete(T entity);

	/** 가게 스코프 조회. 남의 가게 행은 없는 것과 같다. */
	Optional<T> findByIdAndStoreId(Long id, Long storeId);

	/**
	 * 가게 스코프 조회. 없거나 다른 가게의 행이면 404 COMMON_NOT_FOUND — 403으로 존재를 알려 주지 않는다.
	 */
	default T getInStore(long id, long storeId) {
		return findByIdAndStoreId(id, storeId).orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
	}
}
