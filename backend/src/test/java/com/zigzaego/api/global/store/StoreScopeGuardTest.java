package com.zigzaego.api.global.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.zigzaego.api.account.entity.StoreMember;
import com.zigzaego.api.ingredient.repository.IngredientRepository;
import com.zigzaego.api.job.repository.JobRepository;
import com.zigzaego.api.menu.entity.Menu;
import com.zigzaego.api.menu.repository.MenuRepository;
import com.zigzaego.api.purchasedoc.repository.PurchaseDocRepository;
import com.zigzaego.api.recipe.repository.RecipeRepository;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.ResolvableType;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.PagingAndSortingRepository;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.QueryByExampleExecutor;
import org.springframework.util.ClassUtils;

/**
 * 모든 Spring Data 리포지토리가 가게 스코프 규칙(ADR-0002)을 지키는지 검사한다. 새 리포지토리도 자동으로 걸린다.
 *
 * <ul>
 *   <li>엔티티에 {@code storeId}가 있으면 {@link StoreScopedRepository}를 상속하고,
 *       가게 조건 없는 {@code findById} · {@code findAll}을 주는 상위 인터페이스(JpaRepository 등)는 상속하지 않는다.</li>
 *   <li>그런 리포지토리에 직접 선언한 메서드는 이름에 {@code StoreId}가 있거나, {@code @Query}에 {@code :storeId}가 있거나,
 *       {@link StoreScopeExempt}로 이유를 남겨야 한다.</li>
 * </ul>
 */
class StoreScopeGuardTest {

	private static final String BASE_PACKAGE = "com.zigzaego.api";

	/** {@code storeId}가 있지만 가게 스코프 대상이 아닌 테넌트 뿌리 표. 이유를 같이 적는다. */
	private static final Map<Class<?>, String> TENANT_ROOTS = Map.of(
			StoreMember.class, "계정 ↔ 가게 연결 자체. 로그인이 이 표로 계정의 가게를 찾는다");

	/** 가게 조건 없는 조회 · 삭제를 주는 상위 인터페이스. */
	private static final List<Class<?>> UNSCOPED_PARENTS = List.of(
			CrudRepository.class, PagingAndSortingRepository.class, QueryByExampleExecutor.class,
			JpaSpecificationExecutor.class);

	@Test
	void 모든_리포지토리가_가게_스코프_규칙을_지킨다() {
		List<Class<?>> repositories = scanRepositories();

		assertThat(repositories)
				.as("스캔이 비면 검사가 아무것도 안 한 것")
				.contains(JobRepository.class, MenuRepository.class, IngredientRepository.class,
						PurchaseDocRepository.class, RecipeRepository.class);
		assertThat(repositories.stream().flatMap(repo -> violations(repo).stream()).toList()).isEmpty();
	}

	@Test
	void JpaRepository를_상속한_가게_표_리포지토리는_걸린다() {
		assertThat(violations(UnscopedMenuRepository.class))
				.anySatisfy(v -> assertThat(v).contains("StoreScopedRepository"))
				.anySatisfy(v -> assertThat(v).contains("CrudRepository"));
	}

	@Test
	void 가게_조건_없는_메서드를_선언하면_걸린다() {
		assertThat(violations(LeakyMenuRepository.class))
				.singleElement()
				.satisfies(v -> assertThat(v).contains("findByName"));
	}

	@Test
	void 가게_조건_있는_메서드와_예외_표시한_메서드는_통과한다() {
		assertThat(violations(ScopedMenuRepository.class)).isEmpty();
	}

	static List<String> violations(Class<?> repository) {
		Class<?> domain = ResolvableType.forClass(repository).as(Repository.class).resolveGeneric(0);
		if (domain == null || !hasStoreId(domain) || TENANT_ROOTS.containsKey(domain)) {
			return List.of();
		}
		List<String> violations = new ArrayList<>();
		String name = repository.getSimpleName();
		if (!StoreScopedRepository.class.isAssignableFrom(repository)) {
			violations.add(name + ": 가게 표(" + domain.getSimpleName() + ") 리포지토리는 StoreScopedRepository 를 상속해야 한다");
		}
		for (Class<?> parent : UNSCOPED_PARENTS) {
			if (parent.isAssignableFrom(repository)) {
				violations.add(name + ": " + parent.getSimpleName() + " 는 가게 조건 없는 조회를 연다");
			}
		}
		for (Method method : repository.getDeclaredMethods()) {
			if (method.isDefault() || method.isSynthetic() || Modifier.isStatic(method.getModifiers())) {
				continue;
			}
			if (!isScoped(method)) {
				violations.add(name + "." + method.getName()
						+ ": 이름에 StoreId · @Query 에 :storeId · @StoreScopeExempt 중 하나가 필요하다");
			}
		}
		return violations;
	}

	private static boolean isScoped(Method method) {
		if (method.getName().contains("StoreId") || method.isAnnotationPresent(StoreScopeExempt.class)) {
			return true;
		}
		Query query = method.getAnnotation(Query.class);
		return query != null && query.value().contains(":storeId");
	}

	private static boolean hasStoreId(Class<?> type) {
		for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
			for (var field : c.getDeclaredFields()) {
				if (field.getName().equals("storeId")) {
					return true;
				}
			}
		}
		return false;
	}

	/** 이 테스트 안의 예시 인터페이스는 빼고, 프로젝트의 리포지토리 인터페이스를 모두 찾는다. */
	private static List<Class<?>> scanRepositories() {
		var scanner = new ClassPathScanningCandidateComponentProvider(false) {
			@Override
			protected boolean isCandidateComponent(AnnotatedBeanDefinition definition) {
				return definition.getMetadata().isInterface()
						&& !definition.getMetadata().hasAnnotation(NoRepositoryBean.class.getName());
			}
		};
		scanner.addIncludeFilter(new AssignableTypeFilter(Repository.class));
		List<Class<?>> repositories = new ArrayList<>();
		for (BeanDefinition definition : scanner.findCandidateComponents(BASE_PACKAGE)) {
			Class<?> type = ClassUtils.resolveClassName(definition.getBeanClassName(), null);
			if (type.getEnclosingClass() == null) {
				repositories.add(type);
			}
		}
		return repositories;
	}

	// ---- 검사기 자체를 확인하는 예시 (스캔에서는 빠진다)

	interface UnscopedMenuRepository extends JpaRepository<Menu, Long> {
	}

	interface LeakyMenuRepository extends StoreScopedRepository<Menu> {

		Optional<Menu> findByNameAndStoreId(String name, Long storeId);

		List<Menu> findByName(String name);
	}

	interface ScopedMenuRepository extends StoreScopedRepository<Menu> {

		List<Menu> findAllByStoreIdOrderByIdAsc(Long storeId);

		@Query("SELECT m FROM Menu m WHERE m.storeId = :storeId AND m.active = true")
		List<Menu> activeMenus(Long storeId);

		@StoreScopeExempt("예시")
		Set<Menu> findByActive(boolean active);
	}
}
