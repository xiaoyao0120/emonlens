package io.emonlens.server.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import io.emonlens.common.ApiResult;
import io.emonlens.common.EmonLensException;
import io.emonlens.common.ErrorCode;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(EmonLensException.class)
    public ResponseEntity<ApiResult<Void>> handleEmonLens(EmonLensException e) {
        // 限流语义对客户端可执行：HTTP 429 + Retry-After（SDK 指数退避重试，前端提示稍后再试）
        if (e.getErrorCode() == ErrorCode.RATE_LIMITED) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", "5")
                    .body(ApiResult.error(e.getErrorCode(), e.getMessage()));
        }
        return ResponseEntity.ok(ApiResult.error(e.getErrorCode(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResult<Void>> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .findFirst()
                .orElse(ErrorCode.BAD_REQUEST.getDefaultMessage());
        return ResponseEntity.ok(ApiResult.error(ErrorCode.BAD_REQUEST, message));
    }

    /** 请求体不可解析（JSON 语法/结构错误）→ 400 语义，而非笼统 500 */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResult<Void>> handleUnreadable(org.springframework.http.converter.HttpMessageNotReadableException e) {
        return ResponseEntity.ok(ApiResult.error(ErrorCode.BAD_REQUEST,
                "请求体 JSON 无法解析：" + e.getMostSpecificCause().getMessage()));
    }

    /** 缺少必填请求头（如 Idempotency-Key）→ 400 语义，而非笼统 500 */
    @ExceptionHandler(org.springframework.web.bind.MissingRequestHeaderException.class)
    public ResponseEntity<ApiResult<Void>> handleMissingHeader(
            org.springframework.web.bind.MissingRequestHeaderException e) {
        return ResponseEntity.ok(ApiResult.error(ErrorCode.BAD_REQUEST,
                "缺少请求头：" + e.getHeaderName()));
    }

    /** 缺少必填 query 参数 → 400 语义，而非 internal error */
    @ExceptionHandler(org.springframework.web.bind.MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResult<Void>> handleMissingParameter(
            org.springframework.web.bind.MissingServletRequestParameterException e) {
        return ResponseEntity.ok(ApiResult.error(ErrorCode.BAD_REQUEST,
                "缺少请求参数：" + e.getParameterName()));
    }

    /** 路径存在但方法不支持（如 DELETE 打到只读资源）→ 404/405 语义，而非 internal error + ERROR 日志 */
    @ExceptionHandler({
            org.springframework.web.HttpRequestMethodNotSupportedException.class,
            org.springframework.web.servlet.NoHandlerFoundException.class})
    public ResponseEntity<ApiResult<Void>> handleMethodNotSupported(Exception e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResult.error(ErrorCode.NOT_FOUND, "接口或请求方法不存在"));
    }

    /**
     * Content-Type 不支持（例如把表单/纯文本 POST 到 JSON 接口）→ 415，而不是笼统 500。
     * 归到 500 有两个实际后果：监控会把客户端用法错误算成服务端故障；SDK 把 5xx 当可重试，
     * 会反复重试一个永远不可能成功的请求。
     */
    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResult<Void>> handleMediaTypeNotSupported(
            org.springframework.web.HttpMediaTypeNotSupportedException e) {
        String supported = e.getSupportedMediaTypes().isEmpty() ? "application/json"
                : e.getSupportedMediaTypes().toString();
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(ApiResult.error(ErrorCode.BAD_REQUEST,
                        "Content-Type 不支持：" + e.getContentType() + "；请使用 " + supported));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResult<Void>> handleUnexpected(Exception e) {
        log.error("unexpected error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResult.error(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.getDefaultMessage()));
    }
}
