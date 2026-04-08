package io.queryforge.backend.common.logging;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.Ordered;
import org.springframework.core.io.Resource;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.validation.BindingResult;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.io.Reader;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.UUID;

@Aspect
@Component
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE + 50)
@RequiredArgsConstructor
public class ApplicationLayerLoggingAspect {

    private static final int MAX_PREVIEW_LENGTH = 2_000;

    private final ObjectMapper objectMapper;

    @Pointcut("execution(public * io.queryforge.backend..controller..*.*(..)) || execution(public * io.queryforge.backend..*Controller.*(..))")
    public void anyControllerPublicMethod() {
    }

    @Pointcut("execution(public * io.queryforge.backend..service..*.*(..)) || execution(public * io.queryforge.backend..*Service.*(..))")
    public void anyServicePublicMethod() {
    }

    @Around("anyControllerPublicMethod() || anyServicePublicMethod()")
    public Object logControllerAndServiceCalls(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        if (Object.class.equals(method.getDeclaringClass())) {
            return joinPoint.proceed();
        }

        String className = signature.getDeclaringType().getSimpleName();
        String declaringTypeName = signature.getDeclaringTypeName();
        String methodName = signature.getName();
        String traceId = UUID.randomUUID().toString().substring(0, 8);
        String requestContext = resolveRequestContext();
        String layer = declaringTypeName.contains(".controller.") || className.endsWith("Controller")
                ? "CONTROLLER"
                : "SERVICE";
        long startedAtNs = System.nanoTime();

        Object[] args = joinPoint.getArgs();
        String[] parameterNames = signature.getParameterNames();

        log.info(
                "[APP-TRACE][{}][{}][START 시작] {}.{} | request={} | argsCount={}",
                traceId,
                layer,
                className,
                methodName,
                requestContext,
                args == null ? 0 : args.length
        );

        if (log.isDebugEnabled()) {
            logArgs(traceId, className, methodName, parameterNames, args);
        }

        try {
            Object result = joinPoint.proceed();
            long elapsedMs = (System.nanoTime() - startedAtNs) / 1_000_000L;

            log.info(
                    "[APP-TRACE][{}][{}][END 완료] {}.{} | elapsedMs={} | resultType={}",
                    traceId,
                    layer,
                    className,
                    methodName,
                    elapsedMs,
                    result == null ? "null" : result.getClass().getSimpleName()
            );

            if (log.isDebugEnabled()) {
                log.debug(
                        "[APP-TRACE][{}][RESULT 결과] {}.{} -> {}",
                        traceId,
                        className,
                        methodName,
                        previewValue(null, result)
                );
            }
            return result;
        } catch (Throwable exception) {
            long elapsedMs = (System.nanoTime() - startedAtNs) / 1_000_000L;
            log.error(
                    "[APP-TRACE][{}][ERROR 예외] {}.{} | elapsedMs={} | exType={} | message={}",
                    traceId,
                    className,
                    methodName,
                    elapsedMs,
                    exception.getClass().getSimpleName(),
                    truncate(exception.getMessage()),
                    exception
            );
            throw exception;
        }
    }

    private void logArgs(
            String traceId,
            String className,
            String methodName,
            String[] parameterNames,
            Object[] args
    ) {
        if (args == null || args.length == 0) {
            log.debug("[APP-TRACE][{}][ARG 파라미터] {}.{} | no arguments", traceId, className, methodName);
            return;
        }
        for (int index = 0; index < args.length; index++) {
            String paramName = resolveParamName(parameterNames, index);
            String preview = previewValue(paramName, args[index]);
            log.debug(
                    "[APP-TRACE][{}][ARG 파라미터] {}.{} | {}={}",
                    traceId,
                    className,
                    methodName,
                    paramName,
                    preview
            );
        }
    }

    private String resolveParamName(String[] parameterNames, int index) {
        if (parameterNames == null || index < 0 || index >= parameterNames.length) {
            return "arg" + index;
        }
        String candidate = parameterNames[index];
        return (candidate == null || candidate.isBlank()) ? "arg" + index : candidate;
    }

    private String resolveRequestContext() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (!(attrs instanceof ServletRequestAttributes servletAttrs)) {
            return "N/A(non-web thread)";
        }
        HttpServletRequest request = servletAttrs.getRequest();
        if (request == null) {
            return "N/A(no request)";
        }
        String queryString = request.getQueryString();
        String uri = request.getRequestURI();
        if (queryString == null || queryString.isBlank()) {
            return request.getMethod() + " " + uri;
        }
        return request.getMethod() + " " + uri + "?" + queryString;
    }

    private String previewValue(String name, Object value) {
        if (isSensitive(name)) {
            return "<masked>";
        }
        if (value == null) {
            return "null";
        }
        if (value instanceof String s) {
            return truncate(s);
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof Enum<?>) {
            return String.valueOf(value);
        }
        if (value instanceof byte[] bytes) {
            return "byte[" + bytes.length + "]";
        }
        if (value instanceof InputStream || value instanceof Reader) {
            return value.getClass().getSimpleName() + "(stream)";
        }
        if (value instanceof Resource resource) {
            return "Resource(" + truncate(resource.getDescription()) + ")";
        }
        if (value instanceof MultipartFile file) {
            return "MultipartFile(name=" + file.getName() + ", size=" + file.getSize() + ")";
        }
        if (value instanceof HttpServletRequest request) {
            return "HttpServletRequest(" + request.getMethod() + " " + request.getRequestURI() + ")";
        }
        if (value instanceof HttpServletResponse) {
            return "HttpServletResponse";
        }
        if (value instanceof BindingResult bindingResult) {
            return "BindingResult(errorCount=" + bindingResult.getErrorCount() + ")";
        }
        if (value.getClass().isArray()) {
            Class<?> componentType = value.getClass().getComponentType();
            String typeName = componentType == null ? "unknown" : componentType.getSimpleName();
            return "Array(type=" + typeName + ", size=" + Array.getLength(value) + ")";
        }
        return toJsonPreview(value);
    }

    private String toJsonPreview(Object value) {
        try {
            return truncate(objectMapper.writeValueAsString(value));
        } catch (Exception ignored) {
            return truncate(String.valueOf(value));
        }
    }

    private boolean isSensitive(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.contains("password")
                || lower.contains("passwd")
                || lower.contains("token")
                || lower.contains("secret")
                || lower.contains("authorization")
                || lower.contains("credential");
    }

    private String truncate(String value) {
        if (value == null) {
            return "null";
        }
        if (value.length() <= MAX_PREVIEW_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_PREVIEW_LENGTH) + "...(truncated)";
    }
}
