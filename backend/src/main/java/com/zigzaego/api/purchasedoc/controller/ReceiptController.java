package com.zigzaego.api.purchasedoc.controller;

import com.zigzaego.api.global.exception.BusinessException;
import com.zigzaego.api.global.exception.CommonErrorCode;
import com.zigzaego.api.global.response.ApiResponse;
import com.zigzaego.api.global.store.CurrentStoreProvider;
import com.zigzaego.api.purchasedoc.dto.ConfirmResultResponse;
import com.zigzaego.api.purchasedoc.dto.DocsConfirmRequest;
import com.zigzaego.api.purchasedoc.dto.GroupConfirmResult;
import com.zigzaego.api.purchasedoc.dto.LinesConfirmRequest;
import com.zigzaego.api.purchasedoc.dto.MatchConfirmRequest;
import com.zigzaego.api.purchasedoc.dto.ReceiptBoardResponse;
import com.zigzaego.api.purchasedoc.dto.ReceiptConfirmResponse;
import com.zigzaego.api.purchasedoc.dto.ReceiptDetailResponse;
import com.zigzaego.api.purchasedoc.dto.ReceiptMatchConfirmResponse;
import com.zigzaego.api.purchasedoc.dto.ReceiptUpload;
import com.zigzaego.api.purchasedoc.dto.ReviewMatchResponse;
import com.zigzaego.api.purchasedoc.exception.ReceiptConfirmErrorCode;
import com.zigzaego.api.purchasedoc.service.ReceiptBoardService;
import com.zigzaego.api.purchasedoc.service.ReceiptConfirmService;
import com.zigzaego.api.purchasedoc.service.ReceiptJobService;
import com.zigzaego.api.purchasedoc.service.ReceiptReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 영수증 HTTP 주소 8개 (S15P31A308-111 · S15P31A308-112 · S15P31A308-113).
 * 바꾸는 주소는 모두 바뀐 뒤의 화면({@link ReceiptBoardResponse})을 돌려준다. 묶음 id 는 상품명 UTF-8 의 base64url(패딩 없음) —
 * 상품명에 {@code /} 가 있어도 주소에 넣을 수 있게(S15P31A308-113). 확인 목록 서비스는 상품명 그대로 쓰고 여기서 바꿔 싣고 푼다.
 */
@Tag(name = "Receipt", description = "영수증 올리기 · 목록 · 다시 읽기 · 지우기 · 확정. 다른 가게 id · 없는 id · 숫자가 아닌 id 는 모두 404 COMMON_NOT_FOUND")
@RestController
@RequestMapping("/receipts")
@RequiredArgsConstructor
public class ReceiptController {

	private static final Base64.Encoder MATCH_ID_ENCODER = Base64.getUrlEncoder().withoutPadding();
	private static final Base64.Decoder MATCH_ID_DECODER = Base64.getUrlDecoder();

	private final ReceiptJobService receiptJobService;
	private final ReceiptBoardService boardService;
	private final ReceiptReviewService reviewService;
	private final ReceiptConfirmService confirmService;
	private final CurrentStoreProvider currentStoreProvider;

	@Operation(
			summary = "영수증 화면 — 올린 장 · 확인 묶음 · 재료 선택지 · 확정만 남은 영수증",
			description = "files: 영수증 job 으로 올린 장 중 CONFIRMED 가 아닌 것 전부 + CONFIRMED 는 올린 지 7일 안, id 오름차순. "
					+ "st 는 wait · read · done · fail (job 이 끝났는데 읽기 전에 멈춘 장도 fail). "
					+ "matches: 확인 전 줄을 상품명 하나당 하나로 묶은 것 — id 는 상품명 UTF-8 의 base64url(패딩 없음), name 은 상품명. "
					+ "readyDocs: 줄이 모두 자동 확정돼 확정만 하면 되는 영수증. 화면은 job 이 도는 동안 2초마다 부른다.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "영수증 화면")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 없음 (COMMON_UNAUTHORIZED)")
	@GetMapping
	public ApiResponse<ReceiptBoardResponse> board() {
		return ApiResponse.ok(board(currentStoreProvider.currentStoreId()));
	}

	@Operation(
			summary = "영수증 올리기 — 읽기 job 시작",
			description = "multipart 파트 이름 files 로 여러 장(최대 50장, 장당 10MB, JPG · PNG · PDF). 장마다 문서를 만들고 "
					+ "job 하나로 읽는다. 하나라도 걸리면 job · 문서 · 파일을 만들지 않는다. 요청 전체 20MB 를 넘으면 서버가 먼저 막으므로 "
					+ "화면은 20MB 이하로 나눠 여러 번 보낸다(요청마다 job 하나). 원본은 서버에 보관한다(DECISIONS #34). "
					+ "응답은 새 job 이 jobId 인 영수증 화면.",
			requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
					content = @Content(mediaType = MediaType.MULTIPART_FORM_DATA_VALUE,
							schema = @Schema(implementation = UploadForm.class))))
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202", description = "읽기 시작 — 새 장은 st wait")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
			description = "파일 없음 · 빈 파일 (RECEIPT_FILE_REQUIRED) · 51장 이상 (RECEIPT_TOO_MANY_FILES) · "
					+ "한 장 10MB 초과 (RECEIPT_FILE_TOO_LARGE) · JPG · PNG · PDF 아님 (RECEIPT_FILE_NOT_SUPPORTED) · "
					+ "요청 20MB 초과 (COMMON_INVALID_INPUT) · multipart 가 깨짐 (COMMON_MALFORMED_REQUEST)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 없음 (COMMON_UNAUTHORIZED)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503",
			description = "CLOVA 키가 없고 가짜 인식 허용(RECEIPT_ALLOW_FAKE_OCR)이 꺼짐 (RECEIPT_OCR_NOT_CONFIGURED)")
	// consumes 를 걸지 않는다 — 걸면 multipart 가 아닌 요청이 415 예외로 공통 변환기의 500 에 떨어진다.
	// 걸지 않으면 파트 없음과 같게 400 RECEIPT_FILE_REQUIRED (Swagger 는 @RequestPart 파일로 multipart 를 그린다)
	@PostMapping
	@ResponseStatus(HttpStatus.ACCEPTED)
	public ApiResponse<ReceiptBoardResponse> upload(
			@Parameter(description = "영수증 파일들 (image/jpeg · image/png · application/pdf, 장당 10MB, 최대 50장)")
			@RequestPart(value = "files", required = false) List<MultipartFile> files) {
		long storeId = currentStoreProvider.currentStoreId();
		receiptJobService.start(storeId, uploads(files));
		return ApiResponse.ok(board(storeId));
	}

	@Operation(
			summary = "영수증 한 장과 품목 줄",
			description = "files[] 한 칸과 같은 칸 + supplierName(등록 거래처 이름, 없으면 인식한 상호) + lines(줄 번호 오름차순). "
					+ "줄 id 는 POST /receipts/lines/confirm 의 lineId.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "영수증 한 장")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 없음 (COMMON_UNAUTHORIZED)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "없거나 다른 가게 영수증 (COMMON_NOT_FOUND)")
	@GetMapping("/{docId}")
	public ApiResponse<ReceiptDetailResponse> detail(
			@Parameter(description = "문서 id", example = "101") @PathVariable String docId) {
		return ApiResponse.ok(boardService.detail(currentStoreProvider.currentStoreId(), docId));
	}

	@Operation(
			summary = "실패한 장 다시 읽기",
			description = "st fail 인 장만. 새 job 을 만들고 그 장의 줄을 지운 뒤 같은 원본 파일을 다시 읽는다. "
					+ "응답은 그 장이 st wait, jobId 가 새 job 인 영수증 화면.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "다시 읽기 시작")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 없음 (COMMON_UNAUTHORIZED)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "없거나 다른 가게 영수증 (COMMON_NOT_FOUND)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "실패한 장이 아님 (RECEIPT_NOT_FAILED)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503",
			description = "CLOVA 키가 없고 가짜 인식 허용이 꺼짐 (RECEIPT_OCR_NOT_CONFIGURED)")
	@PostMapping("/{docId}/retry")
	public ApiResponse<ReceiptBoardResponse> retry(
			@Parameter(description = "문서 id", example = "101") @PathVariable String docId) {
		long storeId = currentStoreProvider.currentStoreId();
		receiptJobService.retry(storeId, docId);
		return ApiResponse.ok(board(storeId));
	}

	@Operation(
			summary = "확인 전 영수증 지우기",
			description = "문서 · 줄 행만 지운다. 원본 파일은 서버에 남긴다(DECISIONS #34). 사장님이 정한 줄(자동 확정이 아닌 확정 · 무시)이 "
					+ "있거나 확정된 영수증, 읽는 중인 영수증은 지울 수 없다. 응답은 그 장이 빠진 영수증 화면.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "지운 뒤 영수증 화면")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 없음 (COMMON_UNAUTHORIZED)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "없거나 다른 가게 영수증 (COMMON_NOT_FOUND)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
			description = "확인한 줄이 있음 (RECEIPT_ALREADY_CONFIRMED) · 읽는 중 (RECEIPT_IN_PROGRESS)")
	@DeleteMapping("/{docId}")
	public ApiResponse<ReceiptBoardResponse> delete(
			@Parameter(description = "문서 id", example = "101") @PathVariable String docId) {
		long storeId = currentStoreProvider.currentStoreId();
		boardService.delete(storeId, docId);
		return ApiResponse.ok(board(storeId));
	}

	@Operation(
			summary = "묶음 확정 — 같은 상품명 확인 전 줄 전부",
			description = "choice: 재료 id · \"cycle\"(새 주기형 품목) · \"ignore\"(무시). basePerPack: 포장 1개 = 재료 기준 단위 몇(선택, "
					+ "unitNeeded 묶음이면 필수). 날짜를 못 읽은 영수증의 줄은 건너뛰고 skippedLineIds 로 알려 준다. "
					+ "gain 은 추정 재고에 더해진 양(고른 재료 기준 단위).")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "확정 뒤 영수증 화면 + gain")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
			description = "choice 비었음 (RECEIPT_INGREDIENT_REQUIRED) · 단위 미확정인데 basePerPack 없음 (RECEIPT_UNIT_REQUIRED) · "
					+ "basePerPack 0 이하 · 소수 넷째 자리 (RECEIPT_UNIT_INVALID) · 같은 상품명 포장 양 충돌 (RECEIPT_PACK_CONFLICT) · "
					+ "본문이 깨짐 (COMMON_MALFORMED_REQUEST)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 없음 (COMMON_UNAUTHORIZED)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
			description = "없거나 이미 다 정한 묶음 · 묶음 id 가 깨짐 (RECEIPT_MATCH_NOT_FOUND) · 없거나 다른 가게 재료 (COMMON_NOT_FOUND)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
			description = "묶음 줄이 모두 날짜를 못 읽은 영수증 (RECEIPT_DATE_UNKNOWN) · 이미 정한 줄 (RECEIPT_LINE_ALREADY_DECIDED)")
	@PostMapping(value = "/matches/{matchId}/confirm", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ApiResponse<ReceiptMatchConfirmResponse> confirmMatch(
			@Parameter(description = "GET /receipts 의 matches[].id (base64url)", example = "64yA7YyMIDHri6g")
			@PathVariable String matchId,
			@RequestBody MatchConfirmRequest request) {
		long storeId = currentStoreProvider.currentStoreId();
		GroupConfirmResult result = reviewService.confirmGroup(storeId, decodeMatchId(matchId), request.choice(),
				request.basePerPack());
		ReceiptBoardResponse board = encoded(boardService.board(storeId, result.board()));
		return ApiResponse.ok(ReceiptMatchConfirmResponse.of(board, result.gain(), result.skippedLineIds()));
	}

	@Operation(
			summary = "줄 단위 확정",
			description = "줄마다 action INGREDIENT(재료 연결, ingredientId) · CYCLE(새 주기형 품목) · IGNORE(무시). "
					+ "baseQtyPerPack 은 단위 미확정 줄이면 필수. 하나라도 걸리면 아무것도 저장하지 않는다.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "확정 뒤 영수증 화면 + 확정 요약")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
			description = "빈 목록 (RECEIPT_LINE_REQUIRED) · 같은 줄 두 번 (RECEIPT_LINE_DUPLICATE) · 재료 없음 "
					+ "(RECEIPT_INGREDIENT_REQUIRED) · 단위 (RECEIPT_UNIT_REQUIRED · RECEIPT_UNIT_INVALID · RECEIPT_PACK_CONFLICT) · "
					+ "빈 칸 (COMMON_INVALID_INPUT) · action 이 목록 밖 · 본문이 깨짐 (COMMON_MALFORMED_REQUEST)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 없음 (COMMON_UNAUTHORIZED)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "없거나 다른 가게 줄 · 재료 (COMMON_NOT_FOUND)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
			description = "이미 정한 줄 (RECEIPT_LINE_ALREADY_DECIDED) · 읽기가 끝나지 않은 영수증 (RECEIPT_DOC_NOT_PARSED) · "
					+ "날짜 못 읽음 (RECEIPT_DATE_UNKNOWN)")
	@PostMapping(value = "/lines/confirm", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ApiResponse<ReceiptConfirmResponse> confirmLines(@RequestBody LinesConfirmRequest request) {
		long storeId = currentStoreProvider.currentStoreId();
		ConfirmResultResponse confirm = confirmService.confirm(storeId, request.lines());
		return ApiResponse.ok(ReceiptConfirmResponse.of(board(storeId), confirm));
	}

	@Operation(
			summary = "자동 확정 영수증 확정",
			description = "GET /receipts 의 readyDocs[].docId 를 받아 영수증을 확정한다 — 이때 재고에 들어간다(DECISIONS #41). "
					+ "같은 id 는 한 번으로 친다.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "확정 뒤 영수증 화면 + 확정 요약")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
			description = "빈 목록 (RECEIPT_DOC_REQUIRED) · 본문이 깨짐 (COMMON_MALFORMED_REQUEST)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 없음 (COMMON_UNAUTHORIZED)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "없거나 다른 가게 영수증 (COMMON_NOT_FOUND)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
			description = "읽기가 끝나지 않았거나 이미 확정 (RECEIPT_DOC_NOT_PARSED) · 날짜 없음 (RECEIPT_DATE_UNKNOWN) · "
					+ "확인 전 줄이 남음 (RECEIPT_DOC_HAS_PENDING)")
	@PostMapping(value = "/docs/confirm", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ApiResponse<ReceiptConfirmResponse> confirmDocs(@RequestBody DocsConfirmRequest request) {
		long storeId = currentStoreProvider.currentStoreId();
		ConfirmResultResponse confirm = confirmService.confirmDocuments(storeId, request.docIds());
		return ApiResponse.ok(ReceiptConfirmResponse.of(board(storeId), confirm));
	}

	/** Swagger 에 올리기 요청 모양(multipart)을 그리는 용도만. */
	@Schema(description = "영수증 올리기 multipart 요청")
	record UploadForm(
			@Schema(description = "영수증 파일들 (image/jpeg · image/png · application/pdf, 장당 10MB, 최대 50장)")
			List<MultipartFile> files) {
	}

	// ---------------------------------------------------------------- 변환

	private ReceiptBoardResponse board(long storeId) {
		return encoded(boardService.board(storeId));
	}

	/** 묶음 id(상품명)를 base64url 로 바꿔 싣는다. */
	private static ReceiptBoardResponse encoded(ReceiptBoardResponse board) {
		List<ReviewMatchResponse> matches = board.matches().stream()
				.map(m -> new ReviewMatchResponse(encodeMatchId(m.id()), m.name(), m.receipts(), m.lineIds(), m.guess(), m.qty(),
						m.unitNeeded(), m.basePerPack(), m.baseUnit(), m.past(), m.needsReview(), m.reasons()))
				.toList();
		return new ReceiptBoardResponse(board.jobId(), board.files(), matches, board.options(), board.readyDocs());
	}

	static String encodeMatchId(String name) {
		return MATCH_ID_ENCODER.encodeToString(name.getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * base64url(패딩 없음, 우리가 만든 모양 그대로)을 UTF-8 상품명으로. 풀리지 않거나 UTF-8 이 깨졌거나 같은 이름의 다른 표기면
	 * 그런 묶음이 없는 것과 같다.
	 *
	 * @throws BusinessException 404 RECEIPT_MATCH_NOT_FOUND
	 */
	static String decodeMatchId(String matchId) {
		try {
			byte[] bytes = MATCH_ID_DECODER.decode(matchId);
			String name = StandardCharsets.UTF_8.newDecoder()
					.onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT)
					.decode(ByteBuffer.wrap(bytes)).toString();
			if (name.isEmpty() || !encodeMatchId(name).equals(matchId)) {
				throw new BusinessException(ReceiptConfirmErrorCode.MATCH_NOT_FOUND);
			}
			return name;
		} catch (IllegalArgumentException | CharacterCodingException e) {
			throw new BusinessException(ReceiptConfirmErrorCode.MATCH_NOT_FOUND);
		}
	}

	/** multipart 파트 → 영수증 올리기 입력. 파트가 없으면 빈 목록 — 서비스가 400 RECEIPT_FILE_REQUIRED 로 답한다. */
	private static List<ReceiptUpload> uploads(List<MultipartFile> files) {
		List<ReceiptUpload> uploads = new ArrayList<>();
		if (files == null) {
			return uploads;
		}
		for (MultipartFile file : files) {
			if (file == null) {
				uploads.add(null);
				continue;
			}
			try {
				uploads.add(new ReceiptUpload(file.getOriginalFilename(), file.getContentType(), file.getBytes()));
			} catch (IOException e) {
				throw new BusinessException(CommonErrorCode.MALFORMED_REQUEST);
			}
		}
		return uploads;
	}
}
