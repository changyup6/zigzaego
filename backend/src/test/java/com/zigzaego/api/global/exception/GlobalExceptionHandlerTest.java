package com.zigzaego.api.global.exception;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zigzaego.api.global.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

class GlobalExceptionHandlerTest {

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
				.setControllerAdvice(new GlobalExceptionHandler())
				.build();
	}

	@Test
	void 성공_응답은_data만_채운다() throws Exception {
		mockMvc.perform(get("/test/ok"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data").value("hello"))
				.andExpect(jsonPath("$.error").doesNotExist());
	}

	@Test
	void BusinessException은_ErrorCode의_상태와_코드로_응답한다() throws Exception {
		mockMvc.perform(get("/test/conflict"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.data").doesNotExist())
				.andExpect(jsonPath("$.error.code").value("COMMON_CONFLICT"))
				.andExpect(jsonPath("$.error.message").value("이미 처리된 추천입니다."))
				.andExpect(jsonPath("$.error.fieldErrors").doesNotExist());
	}

	@Test
	void 바디_검증_실패는_400과_필드_오류를_돌려준다() throws Exception {
		mockMvc.perform(post("/test/validate")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"quantity\": -1}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("COMMON_INVALID_INPUT"))
				.andExpect(jsonPath("$.error.fieldErrors[0].field").value("quantity"))
				.andExpect(jsonPath("$.error.fieldErrors[0].rejectedValue").value("-1"));
	}

	@Test
	void 읽을_수_없는_바디는_400_MALFORMED_REQUEST() throws Exception {
		mockMvc.perform(post("/test/validate")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{not json"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("COMMON_MALFORMED_REQUEST"));
	}

	@Test
	void 경로_변수_타입_불일치는_400() throws Exception {
		mockMvc.perform(get("/test/items/abc"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("COMMON_INVALID_INPUT"));
	}

	@Test
	void 지원하지_않는_메서드는_405() throws Exception {
		mockMvc.perform(post("/test/ok"))
				.andExpect(status().isMethodNotAllowed())
				.andExpect(jsonPath("$.error.code").value("COMMON_METHOD_NOT_ALLOWED"));
	}

	@Test
	void 예상하지_못한_예외는_500으로_감춘다() throws Exception {
		mockMvc.perform(get("/test/boom"))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.error.code").value("COMMON_INTERNAL_ERROR"))
				.andExpect(jsonPath("$.error.message").value("서버 오류가 발생했습니다."));
	}

	@Test
	void 업로드_크기_초과는_500_이_아니라_400_INVALID_INPUT() throws Exception {
		mockMvc.perform(post("/test/too-large"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("COMMON_INVALID_INPUT"))
				.andExpect(jsonPath("$.error.message").value("업로드 파일이 너무 큽니다."));
	}

	@Test
	void 읽을_수_없는_multipart_는_400_MALFORMED_REQUEST() throws Exception {
		mockMvc.perform(post("/test/bad-multipart"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("COMMON_MALFORMED_REQUEST"));
	}

	@Test
	void 동시_요청이_만든_제약_위반은_409로_감춘다() throws Exception {
		mockMvc.perform(get("/test/duplicate"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("COMMON_CONFLICT"))
				.andExpect(jsonPath("$.error.message").value("현재 상태와 충돌하는 요청입니다."));
	}

	@RestController
	static class TestController {

		@PostMapping("/test/too-large")
		ApiResponse<Void> tooLarge() {
			throw new MaxUploadSizeExceededException(20L * 1024 * 1024);
		}

		@PostMapping("/test/bad-multipart")
		ApiResponse<Void> badMultipart() {
			throw new MultipartException("Failed to parse multipart servlet request");
		}

		@GetMapping("/test/duplicate")
		ApiResponse<Void> duplicate() {
			throw new DataIntegrityViolationException("uq_menu_store_name");
		}

		@GetMapping("/test/ok")
		ApiResponse<String> ok() {
			return ApiResponse.ok("hello");
		}

		@GetMapping("/test/conflict")
		ApiResponse<Void> conflict() {
			throw new BusinessException(CommonErrorCode.CONFLICT, "이미 처리된 추천입니다.");
		}

		@PostMapping("/test/validate")
		ApiResponse<Void> validate(@Valid @RequestBody QuantityRequest request) {
			return ApiResponse.ok();
		}

		@GetMapping("/test/items/{id}")
		ApiResponse<Long> item(@PathVariable Long id) {
			return ApiResponse.ok(id);
		}

		@GetMapping("/test/boom")
		ApiResponse<Void> boom() {
			throw new IllegalStateException("내부 정보");
		}
	}

	record QuantityRequest(@Min(0) Integer quantity) {
	}
}
