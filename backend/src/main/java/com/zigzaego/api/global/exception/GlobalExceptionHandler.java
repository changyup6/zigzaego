package com.zigzaego.api.global.exception;

import com.zigzaego.api.global.response.ApiResponse;
import com.zigzaego.api.global.response.ErrorResponse;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler(BusinessException.class)
	public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException e) {
		ErrorCode errorCode = e.getErrorCode();
		log.info("업무 예외: {} - {}", errorCode.getCode(), e.getMessage());
		return toResponse(errorCode, ErrorResponse.of(errorCode, e.getMessage()));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ApiResponse<Void>> handleBodyValidation(MethodArgumentNotValidException e) {
		List<ErrorResponse.FieldError> fieldErrors = e.getBindingResult().getFieldErrors().stream()
				.map(fe -> new ErrorResponse.FieldError(
						fe.getField(),
						fe.getRejectedValue() == null ? null : String.valueOf(fe.getRejectedValue()),
						fe.getDefaultMessage()))
				.toList();
		return toResponse(CommonErrorCode.INVALID_INPUT, ErrorResponse.of(CommonErrorCode.INVALID_INPUT, fieldErrors));
	}

	@ExceptionHandler(HandlerMethodValidationException.class)
	public ResponseEntity<ApiResponse<Void>> handleParameterValidation(HandlerMethodValidationException e) {
		List<ErrorResponse.FieldError> fieldErrors = e.getParameterValidationResults().stream()
				.flatMap(result -> result.getResolvableErrors().stream()
						.map(error -> new ErrorResponse.FieldError(
								result.getMethodParameter().getParameterName(),
								result.getArgument() == null ? null : String.valueOf(result.getArgument()),
								error.getDefaultMessage())))
				.toList();
		return toResponse(CommonErrorCode.INVALID_INPUT, ErrorResponse.of(CommonErrorCode.INVALID_INPUT, fieldErrors));
	}

	@ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
	public ResponseEntity<ApiResponse<Void>> handleBadParameter(Exception e) {
		return toResponse(CommonErrorCode.INVALID_INPUT, ErrorResponse.of(CommonErrorCode.INVALID_INPUT));
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ApiResponse<Void>> handleMalformedBody(HttpMessageNotReadableException e) {
		return toResponse(CommonErrorCode.MALFORMED_REQUEST, ErrorResponse.of(CommonErrorCode.MALFORMED_REQUEST));
	}

	/** 서블릿 업로드 상한(spring.servlet.multipart.*)을 넘으면 컨트롤러에 닿기 전에 막힌다. 어느 주소든 같은 400. */
	@ExceptionHandler(MaxUploadSizeExceededException.class)
	public ResponseEntity<ApiResponse<Void>> handleUploadTooLarge(MaxUploadSizeExceededException e) {
		return toResponse(CommonErrorCode.INVALID_INPUT, ErrorResponse.of(CommonErrorCode.INVALID_INPUT, "업로드 파일이 너무 큽니다."));
	}

	@ExceptionHandler(MultipartException.class)
	public ResponseEntity<ApiResponse<Void>> handleMalformedMultipart(MultipartException e) {
		return toResponse(CommonErrorCode.MALFORMED_REQUEST, ErrorResponse.of(CommonErrorCode.MALFORMED_REQUEST));
	}

	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	public ResponseEntity<ApiResponse<Void>> handleMethodNotAllowed(HttpRequestMethodNotSupportedException e) {
		return toResponse(CommonErrorCode.METHOD_NOT_ALLOWED, ErrorResponse.of(CommonErrorCode.METHOD_NOT_ALLOWED));
	}

	@ExceptionHandler(NoResourceFoundException.class)
	public ResponseEntity<ApiResponse<Void>> handleNoResource(NoResourceFoundException e) {
		return toResponse(CommonErrorCode.NOT_FOUND, ErrorResponse.of(CommonErrorCode.NOT_FOUND));
	}

	/**
	 * 서비스가 먼저 검사했지만 동시 요청이 그 사이에 끼어든 경우(unique · FK 제약 위반). 서버 오류가 아니라
	 * 현재 상태와 충돌한 요청이므로 409 로 답한다. 제약 이름 같은 내부 정보는 내보내지 않는다.
	 */
	@ExceptionHandler(DataIntegrityViolationException.class)
	public ResponseEntity<ApiResponse<Void>> handleDataIntegrity(DataIntegrityViolationException e) {
		log.warn("데이터 무결성 위반: {}", e.getMostSpecificCause().getMessage());
		return toResponse(CommonErrorCode.CONFLICT, ErrorResponse.of(CommonErrorCode.CONFLICT));
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e) {
		log.error("처리되지 않은 예외", e);
		return toResponse(CommonErrorCode.INTERNAL_ERROR, ErrorResponse.of(CommonErrorCode.INTERNAL_ERROR));
	}

	private ResponseEntity<ApiResponse<Void>> toResponse(ErrorCode errorCode, ErrorResponse body) {
		return ResponseEntity.status(errorCode.getStatus()).body(ApiResponse.fail(body));
	}
}
