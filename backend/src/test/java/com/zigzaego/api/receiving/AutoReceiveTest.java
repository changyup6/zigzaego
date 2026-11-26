package com.zigzaego.api.receiving;

import static com.zigzaego.api.auth.AuthTestSupport.insertStore;
import static com.zigzaego.api.receiving.ReceivingTestData.insertItem;
import static com.zigzaego.api.receiving.ReceivingTestData.insertOrder;
import static com.zigzaego.api.receiving.ReceivingTestData.insertProduct;
import static com.zigzaego.api.receiving.ReceivingTestData.insertSupplier;
import static com.zigzaego.api.receiving.ReceivingTestData.markCancelled;
import static com.zigzaego.api.receiving.ReceivingTestData.markReceived;
import static com.zigzaego.api.receiving.ReceivingTestData.priceRowCount;
import static com.zigzaego.api.support.StoreFixtures.insertIngredient;
import static com.zigzaego.api.support.StoreFixtures.loginNewStore;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.fail;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zigzaego.api.TestcontainersConfiguration;
import com.zigzaego.api.account.entity.Store;
import com.zigzaego.api.account.repository.StoreRepository;
import com.zigzaego.api.global.config.StoreClock;
import com.zigzaego.api.global.exception.BusinessException;
import com.zigzaego.api.global.exception.CommonErrorCode;
import com.zigzaego.api.inventory.dto.InventoryEstimateResponse;
import com.zigzaego.api.inventory.service.InventoryEstimateService;
import com.zigzaego.api.receiving.service.AutoReceiveScheduler;
import com.zigzaego.api.receiving.service.AutoReceiveService;
import com.zigzaego.api.receiving.service.ReceivingService;
import com.zigzaego.api.support.StoreFixtures.LoggedInStore;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * 도착 예정일 자동 입고 배치 (S15P31A308-203). 시계 = 운영 배치 시각 서울 2026-10-20 00:10 (= UTC 10/19 15:10,
 * 로스앤젤레스 10/19 08:10). 대상은 예정일이 가게의 오늘보다 이전인 줄(다음 날 처리). 가게 2개(서울 · 로스앤젤레스).
 * 배치 메서드를 직접 부르고, 같은 날 오후 · 다음 날 새벽은 시계를 옮겨서 본다.
 */
@Import({TestcontainersConfiguration.class, AutoReceiveTest.TestClock.class})
@SpringBootTest
class AutoReceiveTest {

	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
	private static final ZoneId LA = ZoneId.of("America/Los_Angeles");
	/** 서울 10/20 00:10 — 운영 배치 시각. */
	private static final Instant BATCH = Instant.parse("2026-10-19T15:10:00Z");
	/** 서울 10/20 14:00 — 같은 날 오후. */
	private static final Instant AFTERNOON = Instant.parse("2026-10-20T05:00:00Z");
	/** 서울 10/21 00:10 — 다음 날 배치. */
	private static final Instant NEXT_BATCH = Instant.parse("2026-10-20T15:10:00Z");
	private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);

	/** 테스트마다 옮길 수 있는 시계. 테스트 메서드는 한 스레드에서 차례로 돈다. */
	static final class MovableClock extends Clock {

		private volatile Instant instant = BATCH;
		private final ZoneId zone;

		MovableClock(ZoneId zone) {
			this.zone = zone;
		}

		void set(Instant to) {
			this.instant = to;
		}

		@Override
		public ZoneId getZone() {
			return zone;
		}

		@Override
		public Clock withZone(ZoneId other) {
			MovableClock moved = new MovableClock(other);
			moved.instant = instant;
			return moved;
		}

		@Override
		public Instant instant() {
			return instant;
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class TestClock {

		@Bean
		@Primary
		MovableClock autoReceiveClock() {
			return new MovableClock(SEOUL);
		}
	}

	@Autowired
	private MovableClock clock;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private NamedParameterJdbcTemplate namedJdbc;

	@Autowired
	private AutoReceiveService autoReceiveService;

	@Autowired
	private ReceivingService receivingService;

	@Autowired
	private InventoryEstimateService inventoryService;

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private PasswordEncoder passwordEncoder;

	private long seoul;
	private long seoulSupplier;
	private long tomato;
	private long tomatoCan;
	private long seoulOrder;

	private long la;
	private long laSupplier;
	private long onion;
	private long onionBag;
	private long laOrder;

	@BeforeEach
	void setUp() {
		clock.set(BATCH);
		seoul = insertStore(jdbc, "자동입고-서울-" + System.nanoTime());
		seoulSupplier = insertSupplier(jdbc, seoul, "OO상회");
		tomato = insertIngredient(jdbc, seoul, "토마토");
		tomatoCan = insertProduct(jdbc, seoul, seoulSupplier, tomato, "토마토 캔", "캔",
				new BigDecimal("3000"), new BigDecimal("11700"));
		seoulOrder = insertOrder(jdbc, seoul, seoulSupplier, "SENT", OffsetDateTime.parse("2026-10-17T10:00:00+09:00"));

		la = insertStore(jdbc, "자동입고-LA-" + System.nanoTime());
		jdbc.update("UPDATE store SET timezone = 'America/Los_Angeles' WHERE id = ?", la);
		laSupplier = insertSupplier(jdbc, la, "LA상회");
		onion = insertIngredient(jdbc, la, "양파");
		onionBag = insertProduct(jdbc, la, laSupplier, onion, "양파 망", "망",
				new BigDecimal("10000"), new BigDecimal("15000"));
		laOrder = insertOrder(jdbc, la, laSupplier, "SENT", OffsetDateTime.parse("2026-10-17T10:00:00-07:00"));
	}

	// ---------------------------------------------------------------- [대상]

	@Test
	void 대상_예정일이_지난_SENT_줄은_주문_수량대로_예정일_끝_시각에_입고되고_오늘_내일_줄은_그대로() {
		long d18 = insertItem(jdbc, seoul, seoulOrder, tomatoCan, tomato, new BigDecimal("4"), LocalDate.of(2026, 10, 18));
		long d19 = insertItem(jdbc, seoul, seoulOrder, tomatoCan, tomato, new BigDecimal("2.5"), LocalDate.of(2026, 10, 19));
		long today = insertItem(jdbc, seoul, seoulOrder, tomatoCan, tomato, new BigDecimal("3"), TODAY);
		long tomorrow = insertItem(jdbc, seoul, seoulOrder, tomatoCan, tomato, new BigDecimal("1"), LocalDate.of(2026, 10, 21));
		// 다른 가게의 지난 줄 — 서울 가게 배치가 건드리면 안 된다 (ADR-0002)
		long otherStore = insertItem(jdbc, la, laOrder, onionBag, onion, new BigDecimal("1"), LocalDate.of(2026, 10, 18));

		AutoReceiveService.Result result = autoReceiveService.receiveDue(seoul);

		assertThat(result.today()).isEqualTo(TODAY);
		assertThat(result.received()).isEqualTo(2);

		Map<String, Object> r18 = row(d18);
		assertThat((BigDecimal) r18.get("received_qty")).isEqualByComparingTo("4");
		assertThat((BigDecimal) r18.get("received_unit_price")).isEqualByComparingTo("11700");
		assertThat(receivedAt(d18)).isEqualTo(endOfDay(LocalDate.of(2026, 10, 18), SEOUL));

		Map<String, Object> r19 = row(d19);
		assertThat((BigDecimal) r19.get("received_qty")).isEqualByComparingTo("2.5");
		assertThat((BigDecimal) r19.get("received_unit_price")).isEqualByComparingTo("11700");
		// 제때(00:10) 돌아도 예정일 그날 끝 시각(23:59:59.999999) — 그날의 어느 실사보다 뒤
		assertThat(receivedAt(d19)).isEqualTo(endOfDay(LocalDate.of(2026, 10, 19), SEOUL));

		// 배치가 입고한 줄은 auto_received=true — 영수증 연결이 이 표시로 자동 입고 줄을 알아본다 (S15P31A308-238)
		assertThat(r18.get("auto_received")).isEqualTo(true);
		assertThat(r19.get("auto_received")).isEqualTo(true);
		assertUntouched(today);
		assertUntouched(tomorrow);
		assertUntouched(otherStore);
		assertThat(jdbc.queryForObject("SELECT unit_status FROM ingredient WHERE id = ?", String.class, tomato))
				.isEqualTo("LOCKED");
		// 실제로 관측한 단가가 아니므로 price_history 는 쓰지 않는다
		assertThat(priceRowCount(jdbc, tomatoCan)).isZero();
	}

	@Test
	void 대상_거래처_상품_가격이_없으면_단가는_비운_채_입고된다() {
		long loose = insertProduct(jdbc, seoul, seoulSupplier, tomato, "토마토 낱개", null, null, null);
		long item = insertItem(jdbc, seoul, seoulOrder, loose, tomato, new BigDecimal("7"), LocalDate.of(2026, 10, 19));

		autoReceiveService.receiveDue(seoul);

		Map<String, Object> r = row(item);
		assertThat((BigDecimal) r.get("received_qty")).isEqualByComparingTo("7");
		assertThat(r.get("received_unit_price")).isNull();
		assertThat(receivedAt(item)).isEqualTo(endOfDay(LocalDate.of(2026, 10, 19), SEOUL));
		assertThat(priceRowCount(jdbc, loose)).isZero();
	}

	// ---------------------------------------------------------------- [오늘 입고 예정]

	@Test
	void 오늘_입고_예정_당일_배치_뒤에도_오늘_예정_줄은_오후_입고_예정에_today_true_로_남는다() throws Exception {
		// 로그인 토큰이 시계를 따르므로 오후 시각에 로그인한다
		clock.set(AFTERNOON);
		MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
		LoggedInStore mine = loginNewStore(jdbc, passwordEncoder, mockMvc, "자동입고-피드");
		long supplier = insertSupplier(jdbc, mine.storeId(), "OO상회");
		long ingredient = insertIngredient(jdbc, mine.storeId(), "토마토");
		long product = insertProduct(jdbc, mine.storeId(), supplier, ingredient, "토마토 캔", "캔",
				new BigDecimal("3000"), new BigDecimal("11700"));
		long order = insertOrder(jdbc, mine.storeId(), supplier, "SENT", OffsetDateTime.parse("2026-10-17T10:00:00+09:00"));
		long yesterday = insertItem(jdbc, mine.storeId(), order, product, ingredient, new BigDecimal("2"),
				LocalDate.of(2026, 10, 19));
		long today = insertItem(jdbc, mine.storeId(), order, product, ingredient, new BigDecimal("4"), TODAY);

		clock.set(BATCH);
		assertThat(autoReceiveService.receiveDue(mine.storeId()).received()).isEqualTo(1);

		clock.set(AFTERNOON);
		mockMvc.perform(get("/receiving").header(HttpHeaders.AUTHORIZATION, mine.bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.incoming", hasSize(1)))
				.andExpect(jsonPath("$.data.incoming[0].itemId").value(String.valueOf(today)))
				.andExpect(jsonPath("$.data.incoming[0].expectedDate").value("2026-10-20"))
				.andExpect(jsonPath("$.data.incoming[0].today").value(true));
		assertThat((BigDecimal) row(yesterday).get("received_qty")).isEqualByComparingTo("2");
		assertUntouched(today);
	}

	// ---------------------------------------------------------------- [경계]

	@Test
	void 경계_DRAFT_발주서_줄은_그대로() {
		long draft = insertOrder(jdbc, seoul, seoulSupplier, "DRAFT", OffsetDateTime.parse("2026-10-17T10:00:00+09:00"));
		long item = insertItem(jdbc, seoul, draft, tomatoCan, tomato, new BigDecimal("4"), LocalDate.of(2026, 10, 19));

		assertThat(autoReceiveService.receiveDue(seoul).received()).isZero();

		assertUntouched(item);
	}

	@Test
	void 경계_CANCELLED_발주서_줄은_그대로() {
		long cancelled = insertOrder(jdbc, seoul, seoulSupplier, "CANCELLED",
				OffsetDateTime.parse("2026-10-17T10:00:00+09:00"));
		long item = insertItem(jdbc, seoul, cancelled, tomatoCan, tomato, new BigDecimal("4"), LocalDate.of(2026, 10, 19));

		assertThat(autoReceiveService.receiveDue(seoul).received()).isZero();

		assertUntouched(item);
	}

	@Test
	void 경계_안_옴_처리한_줄은_그대로() {
		long item = insertItem(jdbc, seoul, seoulOrder, tomatoCan, tomato, new BigDecimal("4"), LocalDate.of(2026, 10, 19));
		markCancelled(jdbc, item, OffsetDateTime.parse("2026-10-19T18:00:00+09:00"));

		assertThat(autoReceiveService.receiveDue(seoul).received()).isZero();

		assertUntouched(item);
	}

	@Test
	void 경계_이미_입고한_줄은_받은_양_단가_시각이_그대로() {
		long item = insertItem(jdbc, seoul, seoulOrder, tomatoCan, tomato, new BigDecimal("4"), LocalDate.of(2026, 10, 19));
		OffsetDateTime manual = OffsetDateTime.parse("2026-10-19T15:30:00+09:00");
		markReceived(jdbc, item, new BigDecimal("3.5"), new BigDecimal("12000"), manual);

		assertThat(autoReceiveService.receiveDue(seoul).received()).isZero();

		Map<String, Object> r = row(item);
		assertThat((BigDecimal) r.get("received_qty")).isEqualByComparingTo("3.5");
		assertThat((BigDecimal) r.get("received_unit_price")).isEqualByComparingTo("12000");
		assertThat(receivedAt(item)).isEqualTo(manual.toInstant());
	}

	@Test
	void 경계_예정일이_없는_줄은_그대로() {
		long item = insertItem(jdbc, seoul, seoulOrder, tomatoCan, tomato, new BigDecimal("4"), null);

		assertThat(autoReceiveService.receiveDue(seoul).received()).isZero();

		assertUntouched(item);
	}

	@Test
	void 경계_가게_시간대가_다르면_그_가게의_오늘로_판단한다() {
		// 지금 = 로스앤젤레스 10/19 08:10 — 그 가게의 오늘은 10/19 (서울은 이미 10/20)
		long la18 = insertItem(jdbc, la, laOrder, onionBag, onion, new BigDecimal("2"), LocalDate.of(2026, 10, 18));
		long la19 = insertItem(jdbc, la, laOrder, onionBag, onion, new BigDecimal("3"), LocalDate.of(2026, 10, 19));

		AutoReceiveService.Result result = autoReceiveService.receiveDue(la);

		assertThat(result.today()).isEqualTo(LocalDate.of(2026, 10, 19));
		assertThat(result.received()).isEqualTo(1);
		assertThat((BigDecimal) row(la18).get("received_qty")).isEqualByComparingTo("2");
		// 로스앤젤레스 10/18 의 끝 시각
		assertThat(receivedAt(la18)).isEqualTo(endOfDay(LocalDate.of(2026, 10, 18), LA));
		assertUntouched(la19);
	}

	@Test
	void 경계_한_가게에서_예외가_나도_다른_가게_줄은_처리된다() {
		long seoulItem = insertItem(jdbc, seoul, seoulOrder, tomatoCan, tomato, new BigDecimal("4"), LocalDate.of(2026, 10, 19));
		long laItem = insertItem(jdbc, la, laOrder, onionBag, onion, new BigDecimal("2"), LocalDate.of(2026, 10, 18));
		// 없는 가게(시간대를 못 읽어 예외)를 가운데에 끼운다. 실제 DB 의 다른 테스트 가게는 돌지 않게 목록만 목으로
		List<Store> all = List.of(store(seoul), store(-1L), store(la));
		StoreRepository stores = mock(StoreRepository.class);
		when(stores.findAll()).thenReturn(all);
		AutoReceiveScheduler scheduler = new AutoReceiveScheduler(stores, autoReceiveService);

		assertThatCode(scheduler::runDaily).doesNotThrowAnyException();

		assertThat((BigDecimal) row(seoulItem).get("received_qty")).isEqualByComparingTo("4");
		assertThat((BigDecimal) row(laItem).get("received_qty")).isEqualByComparingTo("2");
	}

	// ---------------------------------------------------------------- [재실행]

	@Test
	void 재실행_같은_날_두_번_돌려도_두_번째는_바뀌는_줄이_없다() {
		long a = insertItem(jdbc, seoul, seoulOrder, tomatoCan, tomato, new BigDecimal("4"), LocalDate.of(2026, 10, 18));
		long b = insertItem(jdbc, seoul, seoulOrder, tomatoCan, tomato, new BigDecimal("1"), LocalDate.of(2026, 10, 19));

		assertThat(autoReceiveService.receiveDue(seoul).received()).isEqualTo(2);
		Instant aAt = receivedAt(a);
		Instant bAt = receivedAt(b);
		int prices = priceRowCount(jdbc, tomatoCan);

		AutoReceiveService.Result second = autoReceiveService.receiveDue(seoul);

		assertThat(second.received()).isZero();
		assertThat(second.skipped()).isZero();
		assertThat(receivedAt(a)).isEqualTo(aAt);
		assertThat(receivedAt(b)).isEqualTo(bAt);
		assertThat(priceRowCount(jdbc, tomatoCan)).isEqualTo(prices);
	}

	@Test
	void 재실행_대상_조회_뒤_다른_쪽이_먼저_입고한_줄은_건너뛰고_skipped_로_센다() {
		long raced = insertItem(jdbc, seoul, seoulOrder, tomatoCan, tomato, new BigDecimal("4"), LocalDate.of(2026, 10, 18));
		long next = insertItem(jdbc, seoul, seoulOrder, tomatoCan, tomato, new BigDecimal("1"), LocalDate.of(2026, 10, 19));
		OffsetDateTime manual = OffsetDateTime.parse("2026-10-20T00:05:00+09:00");
		// 조회는 이미 끝났고, raced 줄을 잠그기 직전에 수동 입고가 먼저 커밋된다 → 진짜 recordReceipt 가 409
		ReceivingService racing = mock(ReceivingService.class);
		doAnswer(inv -> {
			long itemId = inv.getArgument(1);
			if (itemId == raced) {
				markReceived(jdbc, raced, new BigDecimal("3"), new BigDecimal("12000"), manual);
			}
			delegate(inv.getArguments());
			return null;
		}).when(racing).recordReceipt(anyLong(), anyLong(), any(), any(), anyBoolean(), any(), anyBoolean());

		AutoReceiveService.Result result = new AutoReceiveService(namedJdbc, new StoreClock(clock), racing).receiveDue(seoul);

		assertThat(result.received()).isEqualTo(1);
		assertThat(result.skipped()).isEqualTo(1);
		assertThat(result.failed()).isZero();
		Map<String, Object> r = row(raced);
		assertThat((BigDecimal) r.get("received_qty")).isEqualByComparingTo("3");
		assertThat((BigDecimal) r.get("received_unit_price")).isEqualByComparingTo("12000");
		assertThat(receivedAt(raced)).isEqualTo(manual.toInstant());
		assertThat((BigDecimal) row(next).get("received_qty")).isEqualByComparingTo("1");
	}

	@Test
	void 재실행_한_줄이_409_아닌_오류로_실패해도_다음_줄은_처리되고_건너뜀으로_세지_않는다() {
		long notFound = insertItem(jdbc, seoul, seoulOrder, tomatoCan, tomato, new BigDecimal("4"), LocalDate.of(2026, 10, 17));
		long broken = insertItem(jdbc, seoul, seoulOrder, tomatoCan, tomato, new BigDecimal("5"), LocalDate.of(2026, 10, 18));
		long next = insertItem(jdbc, seoul, seoulOrder, tomatoCan, tomato, new BigDecimal("1"), LocalDate.of(2026, 10, 19));
		ReceivingService failing = mock(ReceivingService.class);
		doAnswer(inv -> {
			long itemId = inv.getArgument(1);
			if (itemId == notFound) {
				throw new BusinessException(CommonErrorCode.NOT_FOUND);
			}
			if (itemId == broken) {
				throw new IllegalStateException("테스트용 줄 실패");
			}
			delegate(inv.getArguments());
			return null;
		}).when(failing).recordReceipt(anyLong(), anyLong(), any(), any(), anyBoolean(), any(), anyBoolean());

		AutoReceiveService.Result result = new AutoReceiveService(namedJdbc, new StoreClock(clock), failing).receiveDue(seoul);

		assertThat(result.received()).isEqualTo(1);
		assertThat(result.skipped()).isZero();
		assertThat(result.failed()).isEqualTo(2);
		assertUntouched(notFound);
		assertUntouched(broken);
		assertThat((BigDecimal) row(next).get("received_qty")).isEqualByComparingTo("1");
	}

	// ---------------------------------------------------------------- [추정 재고]

	@Test
	void 추정_재고_다음_날_배치로_qty_x_포장_크기만큼_늘고_실사_이전_날짜_줄은_늘지_않는다() {
		// 운영 시각 그대로 — 10/20 09:00 실사, 예정일 10/20 줄은 10/21 00:10 배치에서 received_at = 10/20 끝(실사 뒤),
		// 예정일 10/19 줄은 10/19 끝(실사 앞)
		jdbc.update("INSERT INTO stock_count (store_id, ingredient_id, counted_at, qty) VALUES (?, ?, ?, 5000)",
				seoul, tomato, OffsetDateTime.parse("2026-10-20T09:00:00+09:00"));
		insertItem(jdbc, seoul, seoulOrder, tomatoCan, tomato, new BigDecimal("3"), LocalDate.of(2026, 10, 19));
		insertItem(jdbc, seoul, seoulOrder, tomatoCan, tomato, new BigDecimal("2"), TODAY);
		clock.set(NEXT_BATCH);
		LocalDate nextDay = LocalDate.of(2026, 10, 21);
		BigDecimal before = stock(seoul, tomato, nextDay);

		AutoReceiveService.Result result = autoReceiveService.receiveDue(seoul);

		assertThat(result.today()).isEqualTo(nextDay);
		assertThat(result.received()).isEqualTo(2);
		assertThat(before).isEqualByComparingTo("5000");
		// 2캔 × 3000 g 만 더해진다 (10/19 줄 3캔은 실사 이전)
		assertThat(stock(seoul, tomato, nextDay)).isEqualByComparingTo("11000");
	}

	// ---------------------------------------------------------------- helpers

	private void delegate(Object[] a) {
		receivingService.recordReceipt((long) a[0], (long) a[1], (BigDecimal) a[2], (BigDecimal) a[3], (boolean) a[4],
				(LocalDate) a[5], (boolean) a[6]);
	}

	private static Instant endOfDay(LocalDate date, ZoneId zone) {
		return date.plusDays(1).atStartOfDay(zone).toInstant().minusNanos(1_000);
	}

	private Map<String, Object> row(long itemId) {
		return jdbc.queryForMap(
				"SELECT received_qty, received_unit_price, received_at, auto_received FROM purchase_order_item WHERE id = ?", itemId);
	}

	private Instant receivedAt(long itemId) {
		OffsetDateTime at = jdbc.queryForObject("SELECT received_at FROM purchase_order_item WHERE id = ?",
				OffsetDateTime.class, itemId);
		return at == null ? null : at.toInstant();
	}

	private void assertUntouched(long itemId) {
		Map<String, Object> r = row(itemId);
		assertThat(r.get("received_qty")).isNull();
		assertThat(r.get("received_unit_price")).isNull();
		assertThat(r.get("received_at")).isNull();
	}

	private BigDecimal stock(long storeId, long ingredientId, LocalDate date) {
		for (InventoryEstimateResponse r : inventoryService.estimate(storeId, date)) {
			if (r.ingredientId().equals(String.valueOf(ingredientId))) {
				return r.estimatedQty();
			}
		}
		return fail("재료 %d 의 추정 재고가 없다", ingredientId);
	}

	private static Store store(long id) {
		Store store = mock(Store.class);
		when(store.getId()).thenReturn(id);
		return store;
	}
}
